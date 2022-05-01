package com.aristotlecap.pipeline.steps

import com.aristotlecap.pipeline.models.*

def containerTemplate(String image = env.TOOL_VAULT) {
    return containerTemplate(name: 'vault', image: env.TOOL_VAULT, ttyEnabled: true)
}

def container(Closure body) {
    container('vault') {
        return body()
    }
}

def withRole(VaultRoleId roleId, String vaultToken, Closure body) {
    withCredentials([
        string(credentialsId: vaultToken, variable: 'VAULT_TOKEN'),
        string(credentialsId: roleId.toString(), variable: 'ROLE_ID')
    ]) {
        return body()
    }
}

String generateVaultAuthToken(VaultAppRole role, VaultRoleId roleId, String vaultToken = env.JENKINS_VAULT_TOKEN) {
    generateVaultAuthToken(role.name, roleId, vaultToken)
}

String generateVaultAuthToken(String roleName, VaultRoleId roleId, String vaultToken = env.JENKINS_VAULT_TOKEN) {
    withRole(roleId, vaultToken) {
        String secretId = sh(script: "vault write -field=secret_id -f auth/approle/role/${roleName}/secret-id", returnStdout: true).trim()
        return sh(script: "vault write -field=token auth/approle/login role_id=${ROLE_ID} secret_id=${secretId}", returnStdout: true).trim()
    }
}

void processTemplate(GitRepository repo, String environment, String template, String dest) {
    Boolean isProd = environment == 'prod'
    VaultRoleId vaultRoleId = isProd ? VaultRoleId.PROD_ROLE_ID : VaultRoleId.NONPROD_ROLE_ID
    String appVaultToken = generateVaultAuthToken(new VaultAppRole(repo, isProd), vaultRoleId)
    processTemplate(appVaultToken, new SecretPath(repo, environment), template, dest)
}

void processTemplate(String vaultAuthToken, SecretPath secretPath, String template, String dest) {
    withEnv([
        "VAULT_TOKEN=" + vaultAuthToken,
        "SECRET_ROOT=" + secretPath.secretRoot,
        "SECRET_ROOT_BASE=" + secretPath.secretRootBase
    ]) {
        consulTemplate(template, dest)
    }
}

def processTemplates(GitRepository repo, String environment, Map templates) {
    def isProduction = environment == 'prod'
    def vaultRoleId = isProduction ? VaultRoleId.PROD_ROLE_ID : VaultRoleId.NONPROD_ROLE_ID
    def appVaultToken = generateVaultAuthToken(new VaultAppRole(repo, isProduction), vaultRoleId)
    return processTemplates(appVaultToken, new SecretPath(repo, environment), templates)
}

def processTemplates(GitRepository repo, String environment, Map templates, boolean isProduction) {
    def vaultRoleId = isProduction ? VaultRoleId.PROD_ROLE_ID : VaultRoleId.NONPROD_ROLE_ID
    def appVaultToken = generateVaultAuthToken(new VaultAppRole(repo, isProduction), vaultRoleId)
    return processTemplates(appVaultToken, new SecretPath(repo, environment, isProduction), templates)
}

def processTemplates(String vaultAuthToken, SecretPath secretPath, Map templates) {
    withEnv([
        "VAULT_TOKEN=" + vaultAuthToken,
        "SECRET_ROOT=" + secretPath.secretRoot,
        "SECRET_ROOT_BASE=" + secretPath.secretRootBase
    ]) {

        templates.each { template, secret ->
            consulTemplate(template, secret)
        }
        return templates.values()

    }
}

def processTemplatesForTableau(GitRepository repo, String environment, Map templates, boolean isProduction) {
    def vaultRoleId = isProduction ? VaultRoleId.PROD_ROLE_ID : VaultRoleId.NONPROD_ROLE_ID
    def appVaultToken = generateVaultAuthToken(new VaultAppRole(repo, isProduction), vaultRoleId)
    return processTemplates(appVaultToken, new SecretPath(repo, environment, isProduction, true), templates)
}

void consulTemplate(String template, String dest) {
    sh "consul-template -vault-renew-token=false -once -template ${template}:${dest}"
}

return this
