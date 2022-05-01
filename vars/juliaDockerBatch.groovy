#!/usr/bin/groovy

import groovy.json.JsonOutput

import java.lang.String
import java.util.regex.Pattern

import com.aristotlecap.pipeline.builds.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.models.*

void validate(Map config) {
    Validation validation = new Validation()
    List errors = validation
        .require('nonProdEnvs', List)
        .requireType('builderImage', String)
        .requireType('builderTag', String)
        .requireType('qaEnvs', List)
        .requireType('drEnv', String)
        .requireType('prodEnv', String)
        .requireType('secrets', Map)
        .requireType('disableDocumentation', Boolean)
        .requireType('documentationBranches', List)
        .check(config);
    if (errors.size() > 0) {
        error errors.join('\n')
    }
}

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    Conditionals conditionals = new Conditionals()

    validate(config)
    conditionals.lockWithLabel {
        run(config)
    }
}

void run(Map config) {
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def prompt = new Prompt()
    def julia = new Julia()
    def dockerBuild = new DockerBuild()
    def batchScriptBuild = new BatchScriptBuild()
    def gitStep = new Git()
    def gitBuild = new GitBuild()

    GitRepository repo
    BuilderImage buildImage = BuilderImage.from(env, config)

    Map project;

    pod.node(containers: [ julia.containerTemplate(buildImage.image) ]) {
        stage('Checkout') {
            repo = gitScm.checkout()
        }
        julia.container {
            stage('Julia Setup') {
                project = julia.project()
                currentBuild.displayName = "${repo.branch}-${project.version}-${env.BUILD_NUMBER}"
                julia.addRegistries(config.registries)
                config.preBuildSteps.each { step ->
                    sh step.trim()
                }
            }
            if (julia.canRunTest()) {
                stage('Julia Test') {
                    julia.eval project: '.', expr: 'using Pkg; Pkg.test()'
                }
            }
            if (shouldBuildDocumentation(config, repo)) {
                stage('Build & Deploy Documentation') {
                    julia.runDocumenter(key: 'ghe-jenkins')
                }
            }
        }
    }

    def (a, nonprodImage) = dockerBuild.nonprodBuild()
    def environment = prompt.nonprod(config.nonProdEnvs, config.qaEnvs);
    if (environment?.isEmpty()) {
        return // Stop if user aborts or timeout
    }
    def environmentShort = environment.contains(':')?environment.split(':')[0]:environment
    currentBuild.displayName = "${repo.branch}-${project.version}-${env.BUILD_NUMBER}"
    batchScriptBuild.nonprodBuild(environment, nonprodImage, config.secrets)
    if (config.qaEnvs?.contains(environmentShort) == false) {
        return // Stop if deployed environment is not pre-production
    }

    String changeRequest = prompt.changeRequest()

    if (!changeRequest || changeRequest.isEmpty()) {
        return // Stop if timeout or change request not set
    }

    currentBuild.displayName = "${repo.branch}-${project.version}-${changeRequest}"
    gitBuild.pushReleaseTag(repo, project.version, changeRequest)
    def (approveImage, releaseImage1, releaseImage2) = dockerBuild.pushProdImages(
        repo, nonprodImage, project.version, changeRequest
    )

    String PROJECT_TYPE = 'juliaDockerBatch'

    def parameters = [
        [$class: 'StringParameterValue', name: 'config', value: JsonOutput.toJson(config)],
        [$class: 'StringParameterValue', name: 'repo', value: repo.toJsonString()],
        [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(PROJECT_TYPE)],
        [$class: 'StringParameterValue', name: 'project', value: JsonOutput.toJson(project)],
        [$class: 'StringParameterValue', name: 'releaseImage', value: releaseImage1.toJsonString()],
        [$class: 'StringParameterValue', name: 'changeRequest', value: changeRequest],
        [$class: 'StringParameterValue', name: 'buildNumber', value: env.BUILD_NUMBER.toString()],
    ]

    stage('Trigger Ops Release') {
        build job: env.opsReleaseJob, wait: false, parameters: parameters
    }
}

Boolean shouldBuildDocumentation(Map config, GitRepository repo) {
    Julia julia = new Julia()
    List documentationBranches = config.documentationBranches ?: ['master']
    List patterns = documentationBranches.collect { branch -> Pattern.compile(branch) }
    return !config.disableDocumentation &&
        julia.canBuildDocumentation() &&
        patterns.any { pattern -> repo.branch ==~ pattern }
}
