package com.westernasset.pipeline.models

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static groovy.test.GroovyAssert.*

class ReleaseDockerImageTest {

    @Test
    void testReleaseDockerImage() {
        GitRepository repo = new GitRepository(
            'https://github.westernasset.com/risk/WAgg.jl.git',
            '7e7b415f7fae3e3092a617ab22a6f2bdf165648c',
            'master'
        )

        def env = [
            IMAGE_REPO_URI: 'imagehub.westernasset.com',
            IMAGE_REPO_PROD_KEY: 'docker-local'
        ]

        ReleaseDockerImage releaseDockerImage = new ReleaseDockerImage(repo, env, '1.3.2')

        assert releaseDockerImage.image == 'imagehub.westernasset.com/docker-local/risk/wagg-jl:1.3.2'
        assert releaseDockerImage.tag == '1.3.2'
    }

}
