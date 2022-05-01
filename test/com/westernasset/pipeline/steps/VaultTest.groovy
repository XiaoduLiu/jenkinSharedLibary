package com.aristotlecap.pipeline.steps

import com.aristotlecap.pipeline.models.*
import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static groovy.test.GroovyAssert.*
import static com.lesfurets.jenkins.unit.MethodCall.*

class VaultTest extends BasePipelineTest {

    private def script
    private def repo

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'src/com.aristotlecap/pipeline'
        super.setUp()
        helper.registerAllowedMethod('withEnv', [List.class, Closure.class], null)
        script = loadScript('steps/Vault.groovy')

        repo = new GitRepository(
            'https://github.westernasset.com/risk/WAgg.jl.git',
            '7e7b415f7fae3e3092a617ab22a6f2bdf165648c',
            'master'
        )
    }

    @Test
    void testProcessTemplates() throws Exception {
        def secretPath = new SecretPath(repo, 'dev')
        def templates = [
            'conf/wiser.properties.ctmpl': 'wiser.properties',
            'conf/splunk/inputs.conf.ctmpl': 'inputs.conf',
            'conf/splunk/outputs.conf.ctmpl': 'outputs.conf'
        ]
        script.processTemplates('s.dkmps8sM4c99a9CNNkaYDMkX', secretPath, templates)
        def expectedExecutions = [
            'consul-template -vault-renew-token=false -once -template conf/wiser.properties.ctmpl:wiser.properties',
            'consul-template -vault-renew-token=false -once -template conf/splunk/inputs.conf.ctmpl:inputs.conf',
            'consul-template -vault-renew-token=false -once -template conf/splunk/outputs.conf.ctmpl:outputs.conf'
        ]
        def executions = helper.callStack.findAll { call ->
            call.methodName == 'sh'
        }.eachWithIndex { call, i ->
            assert callArgsToString(call) == expectedExecutions[i]
        }
    }

}
