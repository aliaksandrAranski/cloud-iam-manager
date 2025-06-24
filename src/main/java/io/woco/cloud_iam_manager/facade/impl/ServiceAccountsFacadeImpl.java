package io.woco.cloud_iam_manager.facade.impl;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.woco.cloud_iam_manager.config.CloudConfig;
import io.woco.cloud_iam_manager.config.K8sConfig;
import io.woco.cloud_iam_manager.enums.K8SEventType;
import io.woco.cloud_iam_manager.facade.ServiceAccountsFacade;
import io.woco.cloud_iam_manager.services.CloudServiceIam;
import io.woco.cloud_iam_manager.services.K8sNamespaceService;
import io.woco.cloud_iam_manager.services.K8sServiceAccountService;
import io.woco.cloud_iam_manager.utils.LogUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Main orchestration service for managing IAM bindings for Kubernetes service accounts.
 * <p>
 * This facade coordinates the entire workflow:
 * 1. Discovers namespaces and service accounts
 * 2. Filters service accounts by IAM annotation
 * 3. Implements caching to avoid unnecessary IAM operations
 * 4. Binds service accounts to cloud IAM roles
 * 5. Cleans up obsolete IAM bindings
 * 6. Handles errors and creates audit events
 * <p>
 * The service acts as the main entry point for IAM synchronization operations.
 */
@RequiredArgsConstructor
@Service
public class ServiceAccountsFacadeImpl implements ServiceAccountsFacade {
    private final K8sServiceAccountService k8sServiceAccountService;
    private final K8sNamespaceService k8sNamespaceService;
    private final CloudServiceIam cloudServiceIam;
    private final K8sConfig k8sConfig;
    private final CloudConfig cloudConfig;

    // Cache to track service account -> IAM role mappings to avoid redundant operations
    // Key: service account namespace/name, Value: IAM role/resource ID
    private static final Map<String, String> cache = new HashMap<>();

    /**
     * Synchronizes Kubernetes service accounts with cloud IAM bindings.
     * This is the main entry point for the IAM management workflow.
     *
     * @param useCache Whether to use caching to skip unchanged service accounts
     * @throws ApiException if Kubernetes API operations fail
     */
    @Override
    public void sync(boolean useCache) throws ApiException {
        System.out.println("cache = " + cache);

        // Step 1: Discover target namespaces based on labels
        List<String> namespaces = k8sNamespaceService.getAllNamespacesByLabel(k8sConfig);
        if (namespaces == null || namespaces.isEmpty()) {
            LogUtil.debug("Could not get Namespaces to watch, Could not find Namespaces with labels: " + k8sConfig.getNamespaceLabelToWatch());
            // Clear cache if no namespaces found to prevent stale data
            cache.clear();
            return;
        }
        LogUtil.debug("Namespaces found: " + namespaces);

        // Step 2: Get all service accounts from target namespaces
        List<V1ServiceAccount> serviceAccounts = k8sServiceAccountService.getAllServiceAccounts(namespaces);
        if (serviceAccounts == null || serviceAccounts.isEmpty()) {
            LogUtil.debug("Could not get ServiceAccount objects, Could not find ServiceAccount objects in the provided namespaces: " + namespaces);
            // Clear cache if no service accounts found
            cache.clear();
            return;
        }
        LogUtil.debug("ServiceAccounts found: " + serviceAccounts.stream().map(serviceAccount -> serviceAccount.getMetadata().getName()).toList());

        // Step 3: Filter service accounts that have IAM annotation
        // Only service accounts with the IAM annotation should be managed
        serviceAccounts = serviceAccounts.stream().filter(serviceAccount ->
                serviceAccount.getMetadata() != null &&
                        serviceAccount.getMetadata().getAnnotations() != null &&
                        serviceAccount.getMetadata().getAnnotations().containsKey(k8sConfig.getSaIamAnnotation()) &&
                        serviceAccount.getMetadata().getAnnotations().get(k8sConfig.getSaIamAnnotation()) != null
        ).toList();

        // Keep a copy of all service accounts for cleanup operations
        List<V1ServiceAccount> originalServiceAccounts = new ArrayList<>(serviceAccounts);

        LogUtil.debug("ServiceAccounts found with annotation '" + k8sConfig.getSaIamAnnotation() + "': " + serviceAccounts.stream().map(serviceAccount -> serviceAccount.getMetadata().getName()).toList());

        // Step 4: Apply caching logic to optimize performance
        if (useCache) {
            // Clean up cache entries for service accounts that no longer exist
            Set<String> cacheServiceAccountsNames = new HashSet<>(cache.keySet());
            List<String> clusterSaNames = serviceAccounts.stream().map(serviceAccount -> serviceAccount.getMetadata().getNamespace() + "/" + serviceAccount.getMetadata().getName()).toList();
            cacheServiceAccountsNames.forEach(name -> {
                if (!clusterSaNames.contains(name)) {
                    cache.remove(name);
                }
            });

            // Filter out service accounts that haven't changed since last sync
            List<V1ServiceAccount> changedServiceAccounts = new ArrayList<>();
            for (V1ServiceAccount serviceAccount : serviceAccounts) {
                String serviceAccountRoleCache = cache.get(serviceAccount.getMetadata().getNamespace() + "/" + serviceAccount.getMetadata().getName());

                // Skip if the IAM role annotation hasn't changed
                if (serviceAccountRoleCache != null && serviceAccountRoleCache.equals(serviceAccount.getMetadata().getAnnotations().get(k8sConfig.getSaIamAnnotation()))) {
                    LogUtil.debug("Skipping ServiceAccount: '" + serviceAccount.getMetadata().getName() +
                            "' in namespace: '" + serviceAccount.getMetadata().getNamespace() +
                            "' due to no changes identified in the annotation: '" + k8sConfig.getSaIamAnnotation() + "'");
                    continue;
                }
                changedServiceAccounts.add(serviceAccount);
            }
            serviceAccounts = changedServiceAccounts;
            LogUtil.debug("ServiceAccounts found with changes: " + serviceAccounts.stream().map(serviceAccount -> serviceAccount.getMetadata().getName()).toList());
        }

        // Early exit if no service accounts need processing
        if (serviceAccounts.isEmpty()) {
            LogUtil.debug("Could not find changes in ServiceAccount objects in the provided namespaces: " + namespaces);
            return;
        }

        // Step 5: Create a data structure for cleanup operations
        // This represents the "desired state" of all service accounts that should have IAM bindings
        // Structure: namespace -> serviceAccountName -> exists(true)
        Map<String, Map<String, Boolean>> arrangedServiceAccounts = new HashMap<>();
        for (V1ServiceAccount serviceAccount : originalServiceAccounts) {
            String namespace = serviceAccount.getMetadata().getNamespace();
            String name = serviceAccount.getMetadata().getName();

            // Build nested map structure for efficient lookup during cleanup
            Map<String, Boolean> serviceAccountsInNamespace = arrangedServiceAccounts.getOrDefault(namespace, new HashMap<>());
            serviceAccountsInNamespace.put(name, true);
            arrangedServiceAccounts.put(namespace, serviceAccountsInNamespace);
        }

        // Step 6: Process each service account for IAM binding
        // Track which IAM roles we've already processed to avoid duplicate cleanup operations
        Map<String, Boolean> roles = new HashMap<>();
        for (V1ServiceAccount serviceAccount : serviceAccounts) {
            // Extract the IAM role/resource ID from the annotation
            String role = serviceAccount.getMetadata().getAnnotations().get(k8sConfig.getSaIamAnnotation());

            LogUtil.debug("Processing ServiceAccount: '" + serviceAccount.getMetadata().getName() +
                    "' in namespace: '" + serviceAccount.getMetadata().getNamespace() +
                    "' with role: '" + role + "'");

            try {
                // Bind the service account to the cloud IAM role
                cloudServiceIam.bindIamToServiceAccount(role, serviceAccount.getMetadata().getNamespace(), serviceAccount.getMetadata().getName(), cloudConfig.isPreserveIamBindings());

                // Clean up obsolete bindings for this IAM role (only once per role)
                if (!roles.containsKey(role)) {
                    LogUtil.debug("Cleaning IAM bindings for role: '" + role + "'");
                    // Remove IAM bindings for service accounts that no longer exist
                    cloudServiceIam.cleanIamServiceAccountBindings(role, arrangedServiceAccounts);
                    roles.put(role, true);
                }

                // Update cache with successful binding
                cache.put(serviceAccount.getMetadata().getNamespace() + "/" + serviceAccount.getMetadata().getName(), role);
            } catch (Exception e) {
                // Log error and create Kubernetes event for audit trail
                LogUtil.error("Error while binding IAM to ServiceAccount: '" + serviceAccount.getMetadata().getName() +
                        "' in namespace: '" + serviceAccount.getMetadata().getNamespace() +
                        "' with role: '" + role + "' error: '" + e.getMessage() + "'");

                try {
                    // Create a Kubernetes event attached to the service account for debugging
                    k8sServiceAccountService.addEventToServiceAccount(serviceAccount, K8SEventType.ERROR.getType(), "Bind IAM", e.getMessage());
                } catch (ApiException e1) {
                    // If event creation fails, just log it - don't fail the entire operation
                    LogUtil.error(e1.getMessage());
                }
            }
        }

    }
}
