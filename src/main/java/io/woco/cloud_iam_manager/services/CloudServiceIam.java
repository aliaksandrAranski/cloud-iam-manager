package io.woco.cloud_iam_manager.services;

import io.kubernetes.client.openapi.models.V1ServiceAccount;

import java.util.List;
import java.util.Map;

public interface CloudServiceIam {
    void bindIamToServiceAccount(String iamResourceId, String k8sServiceAccountNamespace, String k8sServiceAccountName, boolean isPreserveExistingBindings);

    void cleanIamServiceAccountBindings(String iamResourceId, Map<String, Map<String, Boolean>> arrangedServiceAccounts);

}
