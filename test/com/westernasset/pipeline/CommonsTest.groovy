package com.westernasset.pipeline

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static com.lesfurets.jenkins.unit.MethodCall.*
import static groovy.test.GroovyAssert.*

class CommonsTest extends BasePipelineTest {

    private def commons

    @Override
    @Before
    void setUp() {
        scriptRoots += 'src/com/westernasset/pipeline'
        helper.registerAllowedMethod('container', [String.class, Closure.class], null)
        super.setUp()
        commons = loadScript('Commons.groovy')
    }

    @Test
    void testLocalBuildSteps() {
        commons.localBuildSteps('Hello Stage', [
            'kubectl get pods',
            'kubectl get nodes',
            'npm run build',
        ].join('\n'))

        assert helper.callStack.findAll { call ->
            call.methodName == 'sh'
        }.any { call ->
            callArgsToString(call)
                .contains('kubectl get pods')
        } == true

        assert helper.callStack.findAll { call ->
            call.methodName == 'sh'
        }.any { call ->
            callArgsToString(call)
                .contains('kubectl get nodes')
        } == true

        assert helper.callStack.findAll { call ->
            call.methodName == 'sh'
        }.any { call ->
            callArgsToString(call)
                .contains('npm run build')
        } == true
    }

    @Test
    void testGetNonProdEnvDetailsForService() {
        assert commons.getNonProdEnvDetailsForService('qa:sc-development') ==
            [deployEnv: 'qa', clusterName: 'sc-development']
        assert commons.getNonProdEnvDetailsForService('dev') == [deployEnv: 'dev', clusterName: null]
    }

    @Test
    void testGetNonProdEnvDetailsForBatch() {
        assert commons.getNonProdEnvDetailsForBatch('dev\nqa') == ['pas-development': 'dev\nqa']
        assert commons.getNonProdEnvDetailsForBatch('dev\nqa:sc-development') ==
            ['pas-development': 'dev', 'sc-development': 'qa']
        assert commons.getNonProdEnvDetailsForBatch('dev') == ['pas-development': 'dev']
    }

    @Test
    void testGetQaEnv() {
        def qaEnvs = commons.getQaEnv(['qa', 'uat'])
        assert qaEnvs.size() == 2
        assert qaEnvs.contains('qa')
        assert qaEnvs.contains('uat')

        qaEnvs = commons.getQaEnv(['qa1', 'qa2', 'uat:us-west-2-production'])
        assert qaEnvs.size() == 3
        assert qaEnvs.contains('qa1')
        assert qaEnvs.contains('qa2')
        assert qaEnvs.contains('uat')

        qaEnvs = commons.getQaEnv(null)
        assert qaEnvs.size() == 0
    }

    @Test
    void testGetProdCluster() {
        assert commons.getProdCluster('pasx') == 'pas-production'
        assert commons.getProdCluster('scx') == 'sc-production'
        assert commons.getProdCluster('us-west-2-production') == 'us-west-2-production'
    }

    @Test
    void testSetJobLabelNonJavaProject() {
        assert commons.setJobLabelNonJavaProject('master', '4ca7f535', '25', '1.4.2') == 'master-1.4.2-25'
        assert commons.setJobLabelNonJavaProject('master', '4ca7f535', '25', 'null') == 'master-4ca7f535-25'
        // Re-enable once we refactor commons.setJobLabelNonJavaProject
        // assert commons.setJobLabelNonJavaProject('master', '4ca7f535', '25', null) == 'master-4ca7f535-25'
    }

}
