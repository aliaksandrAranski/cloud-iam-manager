package io.woco.cloud_iam_manager.services.impl;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1ObjectReference;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.woco.cloud_iam_manager.config.K8sConfig;
import io.woco.cloud_iam_manager.services.K8sServiceAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Kubernetes service account service implementation.
 * Handles CRUD operations for service accounts and manages Kubernetes events for audit/debugging.
 * 
 * This service provides the interface between the application and Kubernetes API for service account management,
 * including event creation for tracking IAM binding operations.
 */
@RequiredArgsConstructor
@Service
public class K8sServiceAccountServiceImpl implements K8sServiceAccountService {
    private final CoreV1Api api;
    private final K8sConfig k8sConfig;

    /**
     * Creates a Kubernetes event associated with a service account.
     * Events are used for audit trails and debugging IAM binding operations.
     * 
     * @param serviceAccount The service account to associate the event with
     * @param type Event type (e.g., "Normal", "Warning", "Error")
     * @param reason Short machine-readable reason for the event
     * @param message Human-readable description of the event
     * @throws ApiException if the event creation fails
     */
    @Override
    public void addEventToServiceAccount(V1ServiceAccount serviceAccount, String type, String reason, String message) throws ApiException {
        // Create and post a Kubernetes event for tracking/debugging purposes
        api.createNamespacedEvent(
                serviceAccount.getMetadata().getNamespace(),
                new CoreV1Event()
                        .type(type)           // Event type (Normal/Warning/Error)
                        .reason(reason)       // Short reason code
                        .message(message)     // Detailed message
                        // Set event timestamp (configurable hours in the past for proper ordering)
                        .firstTimestamp(OffsetDateTime.now().minusHours(k8sConfig.getEventTimingMinusHours()))
                        .metadata(
                                // Generate unique event name based on service account name
                                new V1ObjectMeta().generateName(serviceAccount.getMetadata().getName())
                        )
                        .involvedObject(
                                // Reference to the service account this event is about
                                new V1ObjectReference()
                                        .apiVersion("V1")
                                        .kind("ServiceAccount")
                                        .name(serviceAccount.getMetadata().getName())
                                        .uid(serviceAccount.getMetadata().getUid())
                                        .namespace(serviceAccount.getMetadata().getNamespace())
                        )
        ).execute();
    }

    /**
     * Retrieves a specific service account by name and namespace.
     * 
     * @param namespace The namespace containing the service account
     * @param serviceAccountName The name of the service account
     * @return The service account object
     * @throws ApiException if the service account doesn't exist or API call fails
     */
    @Override
    public V1ServiceAccount getServiceAccount(String namespace, String serviceAccountName) throws ApiException {
        return api.readNamespacedServiceAccount(serviceAccountName, namespace).execute();
    }

    /**
     * Retrieves all service accounts in a specific namespace.
     * 
     * @param namespace The namespace to search in
     * @return List of all service accounts in the namespace
     * @throws ApiException if the API call fails
     */
    @Override
    public List<V1ServiceAccount> getAllServiceAccounts(String namespace) throws ApiException {
        return api.listNamespacedServiceAccount(namespace).execute().getItems().stream().toList();
    }

    /**
     * Retrieves service accounts in a namespace filtered by label selector.
     * 
     * @param namespace The namespace to search in
     * @param label Label selector (e.g., "app=myapp" or "env in (prod,staging)")
     * @return List of service accounts matching the label selector
     * @throws ApiException if the API call fails
     */
    @Override
    public List<V1ServiceAccount> getAllServiceAccounts(String namespace, String label) throws ApiException {
        return api.listNamespacedServiceAccount(namespace).labelSelector(label).execute().getItems().stream().toList();
    }

    /**
     * Retrieves all service accounts across multiple namespaces.
     * This is more efficient than making separate calls per namespace.
     * 
     * @param namespaces List of namespaces to include in the search
     * @return List of service accounts from all specified namespaces
     * @throws ApiException if the API call fails
     */
    @Override
    public List<V1ServiceAccount> getAllServiceAccounts(List<String> namespaces) throws ApiException {
        // Get all service accounts cluster-wide, then filter by namespace
        return api.listServiceAccountForAllNamespaces().execute().getItems().stream()
                // Filter to only include service accounts from our target namespaces
                .filter(serviceAccount -> serviceAccount.getMetadata() != null && 
                                         serviceAccount.getMetadata().getNamespace() != null && 
                                         namespaces.contains(serviceAccount.getMetadata().getNamespace()))
                .toList();
    }

    /**
     * Retrieves service accounts across multiple namespaces with label filtering.
     * Combines namespace filtering with label selector for precise targeting.
     * 
     * @param namespaces List of namespaces to search in
     * @param label Label selector to apply
     * @return List of service accounts matching both namespace and label criteria
     * @throws ApiException if the API call fails
     */
    @Override
    public List<V1ServiceAccount> getAllServiceAccounts(List<String> namespaces, String label) throws ApiException {
        // Get service accounts cluster-wide with label filter, then filter by namespace
        return api.listServiceAccountForAllNamespaces().labelSelector(label).execute().getItems().stream()
                // Additional namespace filtering on top of label filtering
                .filter(serviceAccount -> serviceAccount.getMetadata() != null && 
                                         serviceAccount.getMetadata().getNamespace() != null && 
                                         namespaces.contains(serviceAccount.getMetadata().getNamespace()))
                .toList();
    }
}
