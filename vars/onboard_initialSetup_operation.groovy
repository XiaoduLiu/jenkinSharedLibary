#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def gitOrgName = params.gitOrgName
  def needRepoCreate = params.needRepoCreate
  def gitRepoName = params.gitRepoName
  def projectType = params.projectType
  def appTemplateType = params.appTemplateType
  def lockableResourceLabel = params.lockableResourceLabel
  def groupType = params.groupType
  def vaultTokenTTL = params.vaultTokenTTL

  currentBuild.displayName = "${params.groupType}-${params.gitOrgName}-${params.gitRepoName}-initial-setup"

  stage("Sending the Setup Request") {
    build job: "${env.initialSetupJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'gitOrgName', value: String.valueOf(gitOrgName)],
      [$class: 'StringParameterValue', name: 'needRepoCreate', value: String.valueOf(needRepoCreate)],
      [$class: 'StringParameterValue', name: 'gitRepoName', value: String.valueOf(gitRepoName)],
      [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectType)],
      [$class: 'StringParameterValue', name: 'appTemplateType', value: String.valueOf(appTemplateType)],
      [$class: 'StringParameterValue', name: 'lockableResourceLabel', value: String.valueOf(lockableResourceLabel)],
      [$class: 'StringParameterValue', name: 'groupType', value: String.valueOf(groupType)],
      [$class: 'StringParameterValue', name: 'vaultTokenTTL', value: String.valueOf(vaultTokenTTL)],
      [$class: 'StringParameterValue', name: 'siteDeployTemplate', value: "Teams/WAM Maven Site-Deploy Template"],
      [$class: 'StringParameterValue', name: 'nonProdReleaseTemplate', value: "Teams/WAM Release Non-Prod Deploy Template"],
      [$class: 'StringParameterValue', name: 'nonProdLiquibaseRollbackTemplate', value: "Teams/WAM Liquibase Dev-QA Rollback Template"],
      [$class: 'StringParameterValue', name: 'prodOpsReleaseTemplate', value: "Production/WAM Operation Release Template"],
      [$class: 'StringParameterValue', name: 'prodLiquibaseRollbackTemplate', value: "Production/WAM Prod Liquibase Rollback"],
      [$class: 'StringParameterValue', name: 'nonProdRoot', value: "Operations"],
      [$class: 'StringParameterValue', name: 'prodRoot', value: "Operations"]
    ]
  }

}
