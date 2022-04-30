package com.westernasset.pipeline.models

class VaultAppRole {

    private GitRepository repo
    private boolean isProduction

    private VaultAppRole(GitRepository repo, boolean isProduction) {
        this.repo = repo
        this.isProduction = isProduction
    }

    public String getName() {
        return repo.organization + '-' + repo.name + '-' + (isProduction ? 'prod' : 'nonprod')
    }

}
