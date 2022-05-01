#!/usr/bin/groovy

import java.util.regex.Pattern

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*

void validate(Map config) {
    Validation validation = new Validation()
    List<String> errors = validation
        .requireOneOf('builderTag', 'builderImage')
        .requireType('builderTag', String)
        .requireType('builderImage', String)
        .requireType('registries', List)
        .requireType('disableDocumentation', Boolean)
        .requireType('documentationBranches', List)
        .check(config)
    if (errors.size() > 0) {
        error errors.join('\n')
    }
}

void call(Closure body) {
    Map config = [:]
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
    PodTemplate pod = new PodTemplate()
    Julia julia = new Julia()
    GitScm gitScm = new GitScm()

    GitRepository repo

    BuilderImage buildImage = BuilderImage.from(env, config)
    pod.node(containers: [ julia.containerTemplate(buildImage.image) ]) {
        stage('Checkout') {
            repo = gitScm.checkout()
        }
        julia.container {
            stage('Julia Setup') {
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
}

Boolean shouldBuildDocumentation(Map config, GitRepository repo) {
    Julia julia = new Julia()
    List documentationBranches = config.documentationBranches ?: ['master']
    List patterns = documentationBranches.collect { branch -> Pattern.compile(branch) }
    return !config.disableDocumentation &&
        julia.canBuildDocumentation() &&
        patterns.any { pattern -> repo.branch ==~ pattern }
}
