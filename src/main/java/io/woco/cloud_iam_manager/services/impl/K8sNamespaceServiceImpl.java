package io.woco.cloud_iam_manager.services.impl;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.woco.cloud_iam_manager.config.K8sConfig;
import io.woco.cloud_iam_manager.services.K8sNamespaceService;
import io.woco.cloud_iam_manager.utils.LogUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Kubernetes namespace service implementation.
 * Responsible for discovering and filtering namespaces based on labels.
 * 
 * This service helps scope the IAM management to specific namespaces only,
 * preventing the system from managing IAM bindings across the entire cluster.
 */
@RequiredArgsConstructor
@Service
public class K8sNamespaceServiceImpl implements K8sNamespaceService {
    private final CoreV1Api coreV1Api;

    /**
     * Retrieves all Kubernetes namespaces that match the specified label.
     * This is used to determine which namespaces should have their service accounts managed.
     *
     * @param k8sConfig the configuration containing the label to watch for namespaces
     * @return a list of namespace names that match the specified label
     * @throws ApiException if an error occurs while attempting to retrieve the namespaces
     */
    @Override
    public List<String> getAllNamespacesByLabel(K8sConfig k8sConfig) throws ApiException {
        LogUtil.debug("Retrieving all namespaces by label: '" + k8sConfig.getNamespaceLabelToWatch() + "'");

        V1NamespaceList namespaceList;
        
        // Special case: "all" means manage every namespace in the cluster
        if (k8sConfig.getNamespaceLabelToWatch().equalsIgnoreCase("all")) {
            // Retrieve all namespaces without label filtering
            namespaceList = coreV1Api.listNamespace().timeoutSeconds(10).execute();
        } else {
            // Retrieve only namespaces matching the specified label selector
            // Label selector format: "key=value" or "key" or "key in (value1,value2)"
            namespaceList = coreV1Api.listNamespace().labelSelector(k8sConfig.getNamespaceLabelToWatch()).timeoutSeconds(10).execute();
        }

        // Filter and extract namespace names, ensuring metadata integrity
        return namespaceList.getItems()
                .stream()
                // Safety check: ensure namespace has proper metadata and name
                .filter(namespace -> namespace.getMetadata() != null && 
                                   namespace.getMetadata().getName() != null && 
                                   !namespace.getMetadata().getName().isEmpty())
                // Extract just the namespace name (string) from the metadata
                .map(namespace -> namespace.getMetadata().getName())
                .toList();
    }
}
