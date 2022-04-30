#!/usr/bin/groovy

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.builds.*

def validate(def config) {
  def errors = []
  if (!config.builderTag) {
    errors.add('Missing required field: mavenLib.builderTag')
  } else if (!(config.builderTag instanceof String)) {
    errors.add('Invalid type for field: mavenLib.builderTag (need to be String)')
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

  print config

  validate(config)

  def prompt = new Prompt()
  def ccloudBuild = new CCloudTopicBuild()
  def conditionals = new Conditionals()
  def repo
  def appVersoin
  def dockerFileMap = [:]
  def displayLabel

  currentBuild.displayName = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}"

  conditionals.lockWithLabel {
    def environment = prompt.nonprod(config.nonProdEnvs, config.qaEnvs);
    if (environment == null || environment.isEmpty()) {
      return // Stop if user aborts or timeout
    }

    currentBuild.displayName = "${env.BRANCH_NAME}-${environment}-deploy-${env.BUILD_NUMBER}"

    repo = ccloudBuild.deploy(config.builderTag, null, env.BUILD_NUMBER, env.BRANCH_NAME, environment)

    String changeRequest = prompt.changeRequest()

    if (!changeRequest || changeRequest.isEmpty()) {
      return // Stop if timeout or change request not set
    }

    currentBuild.displayName = "${env.BRANCH_NAME}-${environment}-${env.BUILD_NUMBER}-${changeRequest}"

    stage("Trigger Downstream Job") {
      build job: "${env.opsReleaseJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'projectType', value: "ccloudTopics"],
        [$class: 'StringParameterValue', name: 'buildNumber', value: env.BUILD_NUMBER],
        [$class: 'StringParameterValue', name: 'crNumber', value: changeRequest],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: env.BRANCH_NAME],
        [$class: 'StringParameterValue', name: 'gitScm', value: repo.getScm()],
        [$class: 'StringParameterValue', name: 'builderTag', value: config.builderTag]
      ]
    }
  }
}
