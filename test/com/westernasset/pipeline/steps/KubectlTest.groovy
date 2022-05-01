package com.aristotlecap.pipeline.steps

import com.lesfurets.jenkins.unit.BasePipelineTest
import com.aristotlecap.pipeline.models.GitRepository
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static com.lesfurets.jenkins.unit.MethodCall.*
import static groovy.test.GroovyAssert.*

class KubectlTest extends BasePipelineTest {

    private Yaml yaml = new Yaml()

    private def script
    private def deploymentYaml
    private def serviceYaml

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'src/com.aristotlecap/pipeline'
        super.setUp()
        script = loadScript('steps/Kubectl.groovy')

        deploymentYaml = yaml.load(
            '''\
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: test-repo-dev-deployment
              namespace: test-default
              labels:
                app: test-repo-dev
            spec:
              replicas: 3
              selector:
                matchLabels:
                  app: test-repo-dev
              template:
                metadata:
                  labels:
                    app: test-repo-dev
                spec:
                  containers:
                  - name: test-repo-dev
                    image: nginx:latest
                    imagePullPolicy: Always
                    ports:
                    - containerPort: 80
            '''.stripIndent()
        )

        serviceYaml = yaml.loadAll(
            '''\
            kind: Service
            apiVersion: v1
            metadata:
              name: test-repo-dev-service
            namespace: test-default
            spec:
              selector:
                app: test-repo-dev
              ports:
              - name: http
                protocol: "TCP"
                port: 80
                targetPort: 8080
            ---
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: test-repo-dev-configmap
            data:
              hello: "world"
              foo: "bar"
            '''.stripIndent()
        )

    }

    @Test
    void testKindOrder() throws Exception {
        assert 13 == script.kindOrder('Service')
    }

    @Test
    void testKindOrderDoesNotExist() throws Exception {
        assert 20 == script.kindOrder('DoesNotExist')
    }

    @Test
    void testKubeYamlOrder() {
        assert 16 == script.kubeYamlOrder(deploymentYaml)
    }

    @Test
    void testKubeYamlOrderMultipleDocuments() {
        assert 11 == script.kubeYamlOrder(serviceYaml)
    }

    @Test
    void testSort() {
        Map kubeYamls = [
            'kubernetes/deployment.yaml': deploymentYaml,
            'kubernetes/service.yaml': serviceYaml
        ]

        kubeYamls = script.sort(kubeYamls)

        kubeYamls.eachWithIndex { path, content, index ->
            if (index == 0) {
                assert path == 'kubernetes/service.yaml'
                assert serviceYaml == content
            } else if (index == 1) {
                assert path == 'kubernetes/deployment.yaml'
                assert deploymentYaml == content
            }

        }
    }

    @Test
    void testCreateSecret() {
        script.createSecret('risk-default', 'risk-wiser-secret', [
            'wiser.properties',
            'input.conf',
            'output.conf'
        ])

        assert helper.callStack.findAll { call ->
            call.methodName == 'sh'
        }.any { call ->
            callArgsToString(call)
                .contains('kubectl create secret generic -n risk-default risk-wiser-secret --from-file=wiser.properties --from-file=input.conf --from-file=output.conf')
        } == true
    }

    @Test
    void testCreateSecretWithGitRepository() {
        def repo = new GitRepository(
            'https://github.westernasset.com/risk/WAgg.jl.git',
            '7e7b415f7fae3e3092a617ab22a6f2bdf165648c',
            'master'
        )

        script.createSecret(repo, 'dev', [
            'wiser.properties',
            'input.conf',
            'output.conf'
        ])

        assert helper.callStack.findAll { call ->
            call.methodName == 'sh'
        }.any { call ->
            callArgsToString(call)
                .contains('kubectl create secret generic -n risk-default risk-wagg-jl-dev-secret --from-file=wiser.properties --from-file=input.conf --from-file=output.conf')
        } == true
    }

}
