#!/usr/bin/groovy

import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*
import net.sf.json.JSONObject

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  final String PROJECT_TYPE = 'awsEksctl'

  def conditionals = new Conditionals()
  def awsEksctlBuild = new AwsEksctlBuild()
  def prompt = new Prompt()
  def display = new DisplayLabel()
  def repo

  def branchName = env.BRANCH_NAME
  def buildNumber = env.BUILD_NUMBER

  display.displayLabel(branchName, buildNumber, config.releaseVersion)
  conditionals.lockWithLabel {
    (repo) = awsEksctlBuild.nonProdBuild(config)
    print repo
  }

  def crNumber = prompt.changeRequest();
  if (crNumber == null || crNumber.isEmpty()) {
    return // Stop if user aborts or timeout
  }

  display.displayLabel(branchName, buildNumber, config.releaseVersion + '-' + crNumber)

  config.crNumber = crNumber
  config.branch = branchName
  config.commit = repo.commit
  config.scm = repo.scm
  config.organization = repo.organization
  config.name = repo.name
  config.buildNumber = buildNumber
  print config



  def configJsonString = JSONObject.fromObject(config).toString()
  print configJsonString

  stage('Trigger Ops Release') {
    build job: env.opsReleaseJob, wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'projectType', value: 'awsEksctl'],
      [$class: 'StringParameterValue', name: 'configString', value: configJsonString]
    ]
  }
}
