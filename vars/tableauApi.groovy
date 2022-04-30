#!/usr/bin/groovy

import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.builds.*
import net.sf.json.JSONObject

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  final String PROJECT_TYPE = 'tableauApi'

  def conditionals = new Conditionals()
  def tableauBuild = new TableauApiBuild()
  def prompt = new Prompt()
  def display = new DisplayLabel()
  def repo

  def branchName = env.BRANCH_NAME
  def buildNumber = env.BUILD_NUMBER

  display.displayLabel(branchName, buildNumber)
  conditionals.lockWithLabel {
    (repo) = tableauBuild.build(config, false)
    print repo
  }

  def crNumber = prompt.changeRequest();
  if (crNumber == null || crNumber.isEmpty()) {
    return // Stop if user aborts or timeout
  }

  display.displayLabel(branchName, buildNumber, crNumber)

  def parameterMap = [:]

  def datasourceResources = JSONObject.fromObject(config.datasourceResources).toString()
  def datasourceSettings = JSONObject.fromObject(config.datasourceSettings).toString()
  def workbookResources = JSONObject.fromObject(config.workbookResources).toString()
  def workbookSettings = JSONObject.fromObject(config.workbookSettings).toString()

  stage('Trigger Ops Release') {
    build job: env.opsReleaseJob, wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(env.BUILD_NUMBER)],
      [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(config.releaseVersion)],
      [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(crNumber)],
      [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(repo.branch)],
      [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(repo.commit)],
      [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(repo.scm)],
      [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(repo.organization)],
      [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(repo.name)],
      [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(config.builderTag)],
      [$class: 'StringParameterValue', name: 'parameterJsonString', value: String.valueOf()],
      [$class: 'StringParameterValue', name: 'datasourceResources', value: String.valueOf(datasourceResources)],
      [$class: 'StringParameterValue', name: 'datasourceSettings', value: String.valueOf(datasourceSettings)],
      [$class: 'StringParameterValue', name: 'workbookResources', value: String.valueOf(workbookResources)],
      [$class: 'StringParameterValue', name: 'workbookSettings', value: String.valueOf(workbookSettings)],
      [$class: 'StringParameterValue', name: 'projectType', value: 'tableauApi']
    ]
  }

}
