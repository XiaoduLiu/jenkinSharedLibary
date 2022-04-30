package com.westernasset.pipeline.models

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static groovy.test.GroovyAssert.*

class NonprodDockerImageTest {

    @Test
    void testNonprodDockerImage() {
        GitRepository repo = new GitRepository(
            'https://github.westernasset.com/risk/WAgg.jl.git',
            '7e7b415f7fae3e3092a617ab22a6f2bdf165648c',
            'master'
        )

        def env = [
            IMAGE_REPO_URI: 'imagehub.westernasset.com',
            IMAGE_REPO_NONPROD_KEY: 'docker-local-snapshot'
        ]

        NonprodDockerImage nonprodDockerImage = new NonprodDockerImage(repo, env, '11')

        assert nonprodDockerImage.tag == 'master-7e7b415f7fae3e3092a617ab22a6f2bdf165648c-11'
        assert nonprodDockerImage.image == 'imagehub.westernasset.com/docker-local-snapshot/risk/wagg-jl:master-7e7b415f7fae3e3092a617ab22a6f2bdf165648c-11'
    }

}
