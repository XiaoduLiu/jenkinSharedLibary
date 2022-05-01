#!/usr/bin/groovy

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*

def validate(def config) {
  def errors = []
  if (!config.builderTag) {
    errors.add('Missing required field: KafkaBuild.builderTag')
  } else if (!(config.builderTag instanceof String)) {
    errors.add('Invalid type for field: KafkaBuild.builderTag (need to be String)')
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
  def ccloudBuild = new KafkaBuild()
  def conditionals = new Conditionals()
  def repo
  def appVersoin
  def dockerFileMap = [:]
  def displayLabel

  currentBuild.displayName = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}"

  conditionals.lockWithLabel {
    def answers = prompt.nonprodWithSkip(config.nonProdEnvs, config.nonProdEnvs);
    if (answers == null || answers.isEmpty()) {
      return // Stop if user aborts or timeout
    }

    def environment = answers['environment']
    def skipNonprod = answers['skipNonprod']

    currentBuild.displayName = "${env.BRANCH_NAME}-${environment}-deploy-${env.BUILD_NUMBER}"

    repo = ccloudBuild.deploy(config.builderTag, null, env.BUILD_NUMBER, env.BRANCH_NAME, environment, false, null, null, skipNonprod)

    println repo

    String changeRequest = prompt.changeRequest()

    if (!changeRequest || changeRequest.isEmpty()) {
      return // Stop if timeout or change request not set
    }

    currentBuild.displayName = "${env.BRANCH_NAME}-${environment}-${env.BUILD_NUMBER}-${changeRequest}"

    println repo.scm
    println repo.getScm()

    stage("Trigger Downstream Job") {
      build job: "${env.opsReleaseJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'projectType', value: "ccloud"],
        [$class: 'StringParameterValue', name: 'buildNumber', value: env.BUILD_NUMBER],
        [$class: 'StringParameterValue', name: 'crNumber', value: changeRequest],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: env.BRANCH_NAME],
        [$class: 'StringParameterValue', name: 'gitScm', value: repo.getScm()],
        [$class: 'StringParameterValue', name: 'repo', value: repo.toJsonString()],
        [$class: 'StringParameterValue', name: 'builderTag', value: config.builderTag]
      ]
    }
  }
}
