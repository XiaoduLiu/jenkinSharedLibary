#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def gitOrgName = params.gitOrgName
  def gitRepoName = params.gitRepoName
  def groupType = params.groupType
  def projectType = params.projectType
  def lockableResourceLabel = params.lockableResourceLabel

  currentBuild.displayName = "${params.groupType}-${params.gitOrgName}-${params.gitRepoName}-initial-setup"

  stage("Sending the Setup Request") {
    build job: "${env.initialSetupJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'gitOrgName', value: String.valueOf(gitOrgName)],
      [$class: 'StringParameterValue', name: 'gitRepoName', value: String.valueOf(gitRepoName)],
      [$class: 'StringParameterValue', name: 'groupType', value: String.valueOf(groupType)],
      [$class: 'StringParameterValue', name: 'needProjectSetup', value: 'true'],
      [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectType)],
      [$class: 'StringParameterValue', name: 'lockableResourceLabel', value: String.valueOf(lockableResourceLabel)],
      [$class: 'StringParameterValue', name: 'vaultTokenTTL', value: '5'],
      [$class: 'StringParameterValue', name: 'siteDeployTemplate', value: "Teams/WAM Maven Site-Deploy Template"],
      [$class: 'StringParameterValue', name: 'nonProdReleaseTemplate', value: "Teams/WAM Release Non-Prod Deploy Template"],
      [$class: 'StringParameterValue', name: 'nonProdLiquibaseRollbackTemplate', value: "Teams/WAM Liquibase Dev-QA Rollback Template"],
      [$class: 'StringParameterValue', name: 'prodOpsReleaseTemplate', value: "Production/WAM Operation Release Template"],
      [$class: 'StringParameterValue', name: 'prodLiquibaseRollbackTemplate', value: "Production/WAM Prod Liquibase Rollback"],
      [$class: 'StringParameterValue', name: 'nonProdRoot', value: "Teams"],
      [$class: 'StringParameterValue', name: 'prodRoot', value: "Production"],
    ]
  }

}
