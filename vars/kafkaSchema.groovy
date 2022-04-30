#!/usr/bin/groovy

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.builds.*

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def prompt = new Prompt()
    def ccloudBulder = new CCloudSchemaBuild()

    String environment = prompt.nonprodNoQA(config.nonProdEnvs);
    if (environment == null || environment.isEmpty()) {
      return // Stop if user aborts or timeout
    }

    def repo = ccloudBulder.deploy(config.builderTag, config.schemaPaths, environment, env.BUILD_NUMBER, env.BRANCH_NAME)

    String changeRequest = prompt.changeRequest()

    if (!changeRequest || changeRequest.isEmpty()) {
      return // Stop if timeout or change request not set
    }

    currentBuild.displayName = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}-${changeRequest}"

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
        [$class: 'StringParameterValue', name: 'projectType', value: "kafkaSchema"],
        [$class: 'StringParameterValue', name: 'buildNumber', value: env.BUILD_NUMBER],
        [$class: 'StringParameterValue', name: 'crNumber', value: changeRequest],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: env.BRANCH_NAME],
        [$class: 'StringParameterValue', name: 'repo', value: repo.toJsonString()],
        [$class: 'StringParameterValue', name: 'schemaPaths', value: schemaPathsString],
        [$class: 'StringParameterValue', name: 'builderTag', value: config.builderTag]
      ]
    }

}
