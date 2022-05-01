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
    def repo

    def buildImage = config.buildImage != null ? BuilderImage.fromImage(config.builderImage) : BuilderImage.fromTag(env, config.builderTag)

    conditionals.lockWithLabel {
       (repo) = mavenBuild.snapshotBuild(buildImage)
    }

    String environment = prompt.nonprodNoQA(config.nonProdEnvs);
    if (environment == null || environment.isEmpty()) {
      return // Stop if user aborts or timeout
    }

    mavenBuild = new MavenBuild()
    conditionals.lockWithLabel {
      (repo, appVersion) = mavenBuild.shapshotBuildForKafkaSchemaDeploy(buildImage, environment, config.schemaPaths)
    }

    if (prompt.release()) {
      (repo, appVersion) = mavenBuild.releaseBuild(repo, buildImage)

      String changeRequest = prompt.changeRequest()

      if (!changeRequest || changeRequest.isEmpty()) {
        return // Stop if timeout or change request not set
      }

      currentBuild.displayName = "${env.BRANCH_NAME}-${appVersion}-${env.BUILD_NUMBER}-released-${changeRequest}"

      def schemaPathsString = null
      for (line in config.schemaPaths) {
        if (schemaPathsString == null) {
          schemaPathsString = line
        } else {
          schemaPathsString = schemaPathsString + "::" + line
        }
      }

      stage("Trigger Downstream Job") {
        build job: "${env.opsReleaseJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'projectType', value: "mavenKafkaSchema"],
          [$class: 'StringParameterValue', name: 'buildNumber', value: env.BUILD_NUMBER],
          [$class: 'StringParameterValue', name: 'crNumber', value: changeRequest],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: env.BRANCH_NAME],
          [$class: 'StringParameterValue', name: 'repo', value: repo.toJsonString()],
          [$class: 'StringParameterValue', name: 'releaseVersion', value: appVersion],
          [$class: 'StringParameterValue', name: 'schemaPaths', value: schemaPathsString],
          [$class: 'StringParameterValue', name: 'builderTag', value: config.builderTag]
        ]
      }
    }
}
