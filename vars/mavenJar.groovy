#!/usr/bin/groovy

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*

def validate(def config) {
    def errors = []
    if (!config.builderTag) {
        errors.add('Missing required field: mavenLib.builderTag')
    } else if (!(config.builderTag instanceof String)) {
        errors.add('Invalid type for field: mavenLib.builderTag (need to be String)')
    }
    if (config.downstreamProjects && !(config.downstreamProjects instanceof List)) {
        errors.add('Invalid type for field: mavenLib.downstreamProjects (need to be List of String)')
    }
    if (errors.size() > 0) {
        error errors.join('\n')
    }
}

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    validate(config)

    def conditionals = new Conditionals()
    def mavenBuild = new MavenBuild()
    def prompt = new Prompt()

    def buildImage = config.buildImage != null ? BuilderImage.fromImage(config.builderImage) : BuilderImage.fromTag(env, config.builderTag)

    conditionals.lockWithLabel {
        def (repo) = mavenBuild.snapshotBuild(buildImage)

        if (config.downstreamProjects && config.downstreamProjects.size() > 0) {
            config.downstreamProjects.each { jobName ->
                build job: jobName, wait: false
            }
        }

        if (prompt.release()) {
            mavenBuild.releaseBuild(repo, buildImage)
        }
    }
}
