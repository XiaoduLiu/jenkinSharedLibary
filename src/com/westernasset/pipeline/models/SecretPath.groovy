package com.westernasset.pipeline.models

/*
  def part = (isProd)? "prod":"nonprod"
  def secretRootBase = "secret/${organizationName}/${appGitRepoName}/${part}"
  def secretRoot = (isProd)? "${secretRootBase}":"${secretRootBase}/${env}"
  def appRoleName = "${organizationName}-${appGitRepoName}-${part}"
  */

class SecretPath implements Serializable {

    private GitRepository repo
    private String env
    private boolean isProduction
    private boolean isCountEnv

    SecretPath(GitRepository repo, String env) {
        this.repo = repo
        this.env = env
        this.isProduction = false
        this.isCountEnv = false
    }

    SecretPath(GitRepository repo, String env, boolean isProduction, isCountEnv) {
        this.repo = repo
        this.env = env
        this.isProduction = isProduction
        this.isCountEnv = isCountEnv
    }

    String getSecretRootBase() {
        return 'secret/' + repo.organization + '/' + repo.name + '/' + getPart()
    }

    String getSecretRoot() {
        def str = isProduction() ? secretRootBase : "${secretRootBase}/${env}"
        if (isCountEnv) {
          str = "${secretRootBase}/${env}"
        }
        return str;
    }

    private String getPart() {
        return isProduction() ? 'prod' : 'nonprod'
    }

    private boolean isProduction() {
        boolean bool = this.isProduction
        if (!bool) {
          bool = (env == 'prod')
        }
        return bool
    }

}
