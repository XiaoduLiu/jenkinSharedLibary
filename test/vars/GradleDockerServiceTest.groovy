package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.*

import static com.lesfurets.jenkins.unit.MethodCall.*
import static groovy.test.GroovyAssert.*

// This test is ignored because it is unable to recognize env global variable
// in Conditionals.lockWithLabel(Closure body)
@Ignore
class GradleDockerServiceTest extends BasePipelineTest {

    private def script

    @Override
    @Before
    void setUp() {
        scriptRoots += 'vars'
        super.setUp()
        binding.setVariable('env', [:])
        binding.setVariable('params', [:])
        helper.registerAllowedMethod('lock', [String.class, Closure.class], null)
        script = loadScript('gradleDockerService.groovy')
    }

    @Test
    void testOpsRelease() {
        script.call {
            releaseVersion = '1.0.1'

            builderTag = 'j8u212-m3.6.1-s6.5.1.240'

            nonProdEnvs = ['dev', 'qa']
            qaEnvs = ['qa']
            prodEnv = 'scx'
            drEnv = 'pasx'

            templates = [
                'conf/secrets/first.properties.ctmpl': 'first.properties',
                'conf/secrets/second.properties.ctmpl': 'second.properties'
            ]
        }
    }

}
