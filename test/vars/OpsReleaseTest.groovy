package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.*

import static com.lesfurets.jenkins.unit.MethodCall.*
import static groovy.test.GroovyAssert.*

class OpsReleaseTest extends BasePipelineTest {

    private def script

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'vars'
        super.setUp()
        binding.setVariable('env', [:])
        binding.setVariable('params', [
            prodEnv: 'scx-production'
        ])
        helper.registerAllowedMethod('lock', [String.class, Closure.class], null)
        script = loadScript('opsRelease.groovy')
    }

    @Test
    void testOpsRelease() {
        script.call {}
    }

}
