package com.aristotlecap.pipeline.models

import com.lesfurets.jenkins.unit.BasePipelineTest
import com.aristotlecap.pipeline.models.GitRepository
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static com.lesfurets.jenkins.unit.MethodCall.*
import static groovy.test.GroovyAssert.*

class BasicDockerImageTest {

    @Test
    void testToJsonString() {
        BasicDockerImage image = new BasicDockerImage('mongo', '8.2.1')

        assert image.toJsonString() == '{"name":"mongo","tag":"8.2.1"}'
    }

    @Test
    void testFromJsonString() {
        BasicDockerImage image = BasicDockerImage.fromJsonString('{"name":"mongo","tag":"8.2.1"}')
        assert image.name == 'mongo'
        assert image.tag == '8.2.1'
    }

}
