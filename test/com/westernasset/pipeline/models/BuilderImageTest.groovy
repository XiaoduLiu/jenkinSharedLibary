package com.aristotlecap.pipeline.models

import com.lesfurets.jenkins.unit.BasePipelineTest
import com.aristotlecap.pipeline.models.GitRepository
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static com.lesfurets.jenkins.unit.MethodCall.*
import static groovy.test.GroovyAssert.*

class BuilderImageTest {

    @Test
    void testFromTag() {
        def env = [
            IMAGE_REPO_URI: 'imagehub.westernasset.com',
            IMAGE_REPO_PROD_KEY: 'docker-local',
            IMAGE_BUIDER_REPO: 'jenkins-builder'
        ]
        def builderImage = BuilderImage.fromTag(env, 'gradle-5.4.1')
        assert builderImage.image == 'imagehub.westernasset.com/docker-local/jenkins-builder:gradle-5.4.1'
        assert builderImage.tag == 'gradle-5.4.1'
    }

    @Test
    void testFromImage() {
        def builderImage = BuilderImage.fromImage('imagehub.westernasset.com/docker-local/jenkins-builder:gradle-5.4.1')
        assert builderImage.image == 'imagehub.westernasset.com/docker-local/jenkins-builder:gradle-5.4.1'
        assert builderImage.tag == 'gradle-5.4.1'
    }

    @Test
    void testFromImageSimple() {
        def builderImage = BuilderImage.fromImage('juila:1.5.1')
        assert builderImage.image == 'juila:1.5.1'
        assert builderImage.tag == '1.5.1'
    }

    @Test
    void testFromImageWithLatest() {
        def builderImage = BuilderImage.fromImage('imagehub.westernasset.com/docker-local/jenkins-builder')
        assert builderImage.image == 'imagehub.westernasset.com/docker-local/jenkins-builder:latest'
        assert builderImage.tag == 'latest'
    }

    @Test(expected = IllegalArgumentException.class)
    void testFromImageThrowsException() {
        def builderImage = BuilderImage.fromImage('imagehub.westernasset.com/docker-local/jenkins-builder:latest:1.2.4')
    }

}
