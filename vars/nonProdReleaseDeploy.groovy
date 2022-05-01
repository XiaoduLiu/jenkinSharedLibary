#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  currentBuild.displayName = params.imageTag

  stage("Should I deploy release to Non-Prod?") {
    checkpoint "qadeploy"

    def didTimeout = false

    def userInput
    def deployEnv

    //batch project
    def dockerParameters
    def appArguments

    def projectTypeParam = "${config.projectType}"

    try {
      if (projectTypeParam == 'javaBatch') {
        timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
          userInput = input(
            id: 'Proceed1', message: 'Approve Release?', parameters: [
              [$class: 'ChoiceParameterDefinition', choices: "${params.envs}", description: 'Environments', name: 'env'],
              [$class: 'StringParameterDefinition', defaultValue: ' --rm -v /docker/app-data/sas/qa/risk_user_app:/attribution --name loader2saskafka', description: 'Docker Parameters', name: 'dockerParameters'],
              [$class: 'StringParameterDefinition', defaultValue: '', description: 'Applicaton Arguments', name: 'appArguments']
          ])
        }
        deployEnv = userInput['env']
        dockerParameters = userInput['dockerParameters']
        appArguments = userInput['appArguments']
      } else {
        echo "envs -> ${params.nonProdEnvs}"
        timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
          userInput = input(
            id: 'Proceed1', message: 'Approve Non-Prod Deploy?', parameters: [
              [$class: 'ChoiceParameterDefinition', choices: "${params.nonProdEnvs}", description: 'Environments', name: 'env']
          ])
        }
        deployEnv = userInput
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }

    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      deployLogic(
        "${params.gitScm}",
        "${params.gitBranchName}",
        "${params.gitCommit}",
        "${params.buildNumber}",
        "${deployEnv}",
        "${params.organizationName}",
        "${params.appGitRepoName}",
        "${params.liquibaseChangeLog}",
        "${env.liquibaseProjectFolder}",
        "${params.liquibaseBuilderTag}",
        "${params.appDtrRepo}",
        "${params.userReleaseVersion}",
        "${params.projectType}",
        "${params.builderTag}",
        "${params.templates}",
        "${params.secrets}",
        "${params.removeStack}",
        "${params.releaseImageTag}",
        "${dockerParameters}",
        "${appArguments}"
      )
    }
  }
}

def deployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                organizationName, appGitRepoName, liquibaseChangeLog,liquibaseProjectFolder, liquibaseBuilderTag,
                appDtrRepo, userReleaseVersion, projectType, builderTag,
                templates, secrets, removeStack,
                releaseImageTag, dockerParameters, appArguments) {
  node('agent') {

    def commons = new com.aristotlecap.pipeline.Commons()
    try {
      // Clean workspace & check out the GIT
      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-genkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      currentBuild.displayName = releaseImageTag + '-' + deployEnv

      def stackName = organizationName + '-' + appGitRepoName + '-' + deployEnv
      echo "stackName = " + stackName

      def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'

      def secretRoot="secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
      echo secretRoot

      def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
      echo workspace

      if (fileExists("${workspace}/conf/env/${deployEnv}.groovy")) {
        echo "Yes, ${workspace}/conf/env/${deployEnv}.groovy exists"
        load "${workspace}/conf/env/${deployEnv}.groovy"
      }

      def appStackName = "${stackName}"
      if (env.STACK_NAME) {
        appStackName = "${env.STACK_NAME}"
      }

      if (projectType == 'javaBatch') {
        commons.nonProdBatchRunLogic(gitScm, gitBranchName, gitCommit,
                                     buildNumber, deployEnv,
                                     organizationName, appGitRepoName,
                                     liquibaseChangeLog,
                                     liquibaseProjectFolder,
                                     liquibaseBuilderTag,
                                     releaseImageTag, dockerParameters,
                                     appArguments)
      } else {
        if (liquibaseChangeLog != 'null') {
          //do liquibase step
          stage("Liquibase Database Update") {
            echo "EXECUTE LIQUIDBASE"
            commons.liquibaseProcess(pasDtrUri,
                                     pasBuilder,
                                     liquibaseProjectFolder,
                                     workspace,
                                     liquibaseChangeLog,
                                     liquibaseBuilderTag,
                                     secretRoot,
                                     appRoleName,
                                     false,
                                     null,
                                     "non-prod")
          }
        }
        stage("Deploy to non-prod") {
          echo "EXECUTE QA DEPLOY"
          commons.deploy(workspace,
                        releaseImageTag,
                        pasDtrUri,
                        secretRoot,
                        appStackName,
                        appRoleName,
                        organizationName,
                        appGitRepoName,
                        deployEnv,
                        templates,
                        secrets,
                        removeStack)
        }
      }
    } catch (err) {
	    currentBuild.result = 'FAILED'
	    throw err
	  }
  }
}
