package io.woco.cloud_iam_manager.services.impl;

import com.google.cloud.iam.admin.v1.IAMClient;
import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import io.woco.cloud_iam_manager.config.CloudConfig;
import io.woco.cloud_iam_manager.services.CloudServiceIam;
import io.woco.cloud_iam_manager.utils.LogUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Cloud Platform IAM service implementation.
 * Handles binding and cleaning IAM roles for Kubernetes service accounts in GCP.
 * 
 * This service manages the connection between Kubernetes service accounts and GCP IAM roles,
 * allowing K8s workloads to authenticate with GCP services using Workload Identity.
 */
@ConditionalOnExpression("'${app-conf.cloud-config.cloud-provider}'.equals(\"GCP\")")
@RequiredArgsConstructor
@Service
public class CloudServiceGcpIamImpl implements CloudServiceIam {
    private final CloudConfig cloudConfig;
    private final IAMClient iamClient;

    /**
     * Binds a Kubernetes service account to a GCP IAM service account.
     * This enables Workload Identity - allowing K8s pods to authenticate as GCP service accounts.
     * 
     * @param iamResourceId The GCP service account email/ID to bind to
     * @param k8sServiceAccountNamespace The Kubernetes namespace containing the service account
     * @param k8sServiceAccountName The Kubernetes service account name
     * @param isPreserveExistingBindings Whether to keep existing IAM bindings or replace them
     */
    @Override
    public void bindIamToServiceAccount(String iamResourceId, String k8sServiceAccountNamespace, String k8sServiceAccountName, boolean isPreserveExistingBindings) {
        // Build the full GCP service account resource name
        String resourceName = "projects/%s/serviceAccounts/%s".formatted(cloudConfig.getGcpProjectId(), iamResourceId);

        List<Binding> bindings = new ArrayList<>();
        
        // If preserving existing bindings, get current policy first
        if (isPreserveExistingBindings) {
            bindings = new ArrayList<>(iamClient.getIamPolicy(resourceName).getBindingsList());
        }

        // Create the new binding for Workload Identity
        // Format: serviceAccount:PROJECT_ID.svc.id.goog[NAMESPACE/SERVICE_ACCOUNT_NAME]
        bindings.add(
                Binding.newBuilder()
                        .setRole("roles/" + cloudConfig.getIamBindingRole())
                        .addMembers("serviceAccount:%s.svc.id.goog[%s/%s]".formatted(cloudConfig.getGcpProjectId(), k8sServiceAccountNamespace, k8sServiceAccountName))
                        .build()
        );

        // Apply the updated policy to the GCP service account
        Policy policy = Policy.newBuilder()
                .addAllBindings(bindings)
                .build();

        iamClient.setIamPolicy(resourceName, policy);
    }

    /**
     * Cleans up obsolete IAM bindings by removing service accounts that no longer exist in Kubernetes.
     * This prevents orphaned IAM bindings from accumulating over time.
     * 
     * @param iamResourceId The GCP service account to clean bindings for
     * @param arrangedServiceAccounts Map of current K8s service accounts: namespace -> serviceAccount -> exists
     */
    @Override
    public void cleanIamServiceAccountBindings(String iamResourceId, Map<String, Map<String, Boolean>> arrangedServiceAccounts) {
        String resourceName = "projects/%s/serviceAccounts/%s".formatted(cloudConfig.getGcpProjectId(), iamResourceId);
        
        LogUtil.info("Starting IAM cleanup for service account:" + iamResourceId);
        LogUtil.debug("Expected service accounts: " + arrangedServiceAccounts);

        // Get current IAM policy bindings
        List<Binding> bindings = new ArrayList<>(iamClient.getIamPolicy(resourceName).getBindingsList());
        List<Binding> updatedBindings = new ArrayList<>(bindings);
        
        LogUtil.debug("Found " + bindings.size() + " existing bindings");

        // Process each binding to clean up obsolete entries
        for (Binding binding : bindings) {
            // Only process bindings for our managed IAM role
            if (binding.getRole().equals("roles/" + cloudConfig.getIamBindingRole())) {
                LogUtil.debug("Processing binding for role: " + binding.getRole());
                updatedBindings.remove(binding);

                // Filter members to keep only valid ones
                List<String> members = new ArrayList<>();
                for (String member : binding.getMembersList()) {
                    members.add(member);
                    LogUtil.debug("Processing member: " + member);

                    // Keep non-project members (external service accounts, users, etc.)
                    if (!member.contains(cloudConfig.getGcpProjectId())) {
                        LogUtil.debug("Keeping non-project member: " + member);
                        continue;
                    }

                    // Extract namespace and service account from Workload Identity format
                    // Format: serviceAccount:PROJECT_ID.svc.id.goog[NAMESPACE/SERVICE_ACCOUNT_NAME]
                    String content = member.substring(member.indexOf("[") + 1, member.indexOf("]")); // Extract "namespace/serviceAccount"
                    String[] parts = content.split("/");
                    String namespace = parts[0]; // Extract "namespace"
                    String serviceAccount = parts[1]; // Extract "serviceAccount"
                    
                    LogUtil.debug("Extracted namespace: '" + namespace + "', serviceAccount: '" + serviceAccount + "'");

                    // Remove member if the corresponding K8s service account no longer exists
                    if (!arrangedServiceAccounts.containsKey(namespace) || !arrangedServiceAccounts.get(namespace).containsKey(serviceAccount)) {
                        LogUtil.info("Removing obsolete member: " + member + " (namespace: " + namespace + ", serviceAccount: " + serviceAccount + ")");
                        members.remove(member);
                    } else {
                        LogUtil.debug("Keeping valid member: " + member + " (namespace: " + namespace + ", serviceAccount: " + serviceAccount + ")");
                    }
                }

                // Create updated binding with filtered members
                LogUtil.debug("Creating updated binding with " + members.size() + " members");
                updatedBindings.add(Binding.newBuilder()
                        .setRole(binding.getRole())
                        .addAllMembers(members)
                        .build());
            }
        }

        // Apply the cleaned-up policy
        Policy policy = Policy.newBuilder()
                .addAllBindings(updatedBindings)
                .build();

        LogUtil.info("Updating IAM policy for GCP service account: " + iamResourceId + " with " + updatedBindings.size() + " bindings");
        iamClient.setIamPolicy(resourceName, policy);
        LogUtil.info("Successfully updated IAM policy for GCP service account: " + iamResourceId);
    }
}
