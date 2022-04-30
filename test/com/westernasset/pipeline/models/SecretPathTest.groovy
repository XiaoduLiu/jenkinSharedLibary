package com.westernasset.pipeline.models

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static groovy.test.GroovyAssert.*

class SecretPathTest {

    GitRepository repo

    @Before
    void setup() {
        repo = new GitRepository(
            'https://github.westernasset.com/risk/WAgg.jl.git',
            '7e7b415f7fae3e3092a617ab22a6f2bdf165648c',
            'master'
        )
    }

    @Test
    void testNonprodSecretPath() {
        SecretPath secretPath = new SecretPath(repo, 'dev')

        assert secretPath.secretRootBase == 'secret/risk/WAgg.jl/nonprod'
        assert secretPath.secretRoot == 'secret/risk/WAgg.jl/nonprod/dev'
    }

    @Test
    void testProdSecretPath() {
        SecretPath secretPath = new SecretPath(repo, 'prod')

        assert secretPath.secretRootBase == 'secret/risk/WAgg.jl/prod'
        assert secretPath.secretRoot == 'secret/risk/WAgg.jl/prod'
    }

}