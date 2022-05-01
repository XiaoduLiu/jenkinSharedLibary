package com.aristotlecap.pipeline.steps

import com.lesfurets.jenkins.unit.BasePipelineTest
import com.aristotlecap.pipeline.models.GitRepository
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static com.lesfurets.jenkins.unit.MethodCall.*
import static groovy.test.GroovyAssert.*

class TomlReaderTest extends BasePipelineTest {

    private def script

    @Override
    @Before
    void setUp() {
        scriptRoots += 'src/com.aristotlecap/pipeline'
        super.setUp()
        script = loadScript('util/TomlReader.groovy')
    }

    @Test
    void testProperties() {
        String projectToml =
        '''\
        name = "TestJuliaDockerBatch"
        uuid = "1e3f82ef-3fd9-4bb6-b2ed-46435d2c8eaf"
        authors = ["Kevin Yen <kevin.yen@westernasset.com>"]
        version = "0.1.0"

        [deps]
        Test = "8dfed614-e22c-5e08-85e1-65c5234f0b40"
        '''

        println(script.read(projectToml))
    }

}
