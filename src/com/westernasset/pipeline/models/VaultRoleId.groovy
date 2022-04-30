package com.westernasset.pipeline.models

enum VaultRoleId {

    NONPROD_ROLE_ID('nonprod-role-id'),
    PROD_ROLE_ID('prod-role-id')

    public final String id

    private VaultRoleId(String id) {
        this.id = id
    }

    @Override
    public String toString() {
        return id
    }

}
