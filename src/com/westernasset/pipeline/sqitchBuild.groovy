package com.westernasset.pipeline;

import groovy.lang.Tuple

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          qaEnvs, continuousDeploymentEnvs, releaseVersion, verifyFromChange) {

  def organizationName
  def appGitRepoName
  def gitScm
  def gitCommit

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"

  def commons = new com.westernasset.pipeline.Commons()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sqitch', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
  ])  {
    node(POD_LABEL) {
      try {
        stage ('Clone') {
          // Clean workspace before doing anything
          deleteDir()
          checkout scm

          gitCommit=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
          echo gitCommit

          String gitRemoteURL = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
          echo gitRemoteURL

          gitScm = "git@github.westernasset.com:" + gitRemoteURL.drop(32)
          echo gitScm

          String shortName = gitRemoteURL.drop(32).reverse().drop(4).reverse()
          echo shortName

          def names = shortName.split('/')

          echo names[0]
          echo names[1]

          organizationName = names[0]
          appGitRepoName = names[1]

          appDtrRepo = organizationName + '/' + appGitRepoName
          echo "appDtrRepo -> ${appDtrRepo}"
        }

        stage('Auto Deploy') {
          if (continuousDeploymentEnvs != 'null') {
            def autoEnvs = continuousDeploymentEnvs.split("\n")
            def dem = autoEnvs.length
            def i = 0
            while(i<dem) {
              def autoEnv = autoEnvs[i]
              print 'auto deploy to -> ' + autoEnv
              commons.snowflackScriptTokenReplacement(autoEnv);
              def hasTarget = commons.checkSqichTarget(autoEnv)
              def changeFrom = commons.snowflakeDeployStatus(organizationName, appGitRepoName, autoEnv, false, verifyFromChange, hasTarget)
              commons.snowflakeDeploy(autoEnv, false, 'deploy', hasTarget)
              commons.snowflakeDeploy(autoEnv, false, 'verify', changeFrom, hasTarget)
              i=i+1
            }
            echo continuousDeploymentEnvs
            def tagEnvs = continuousDeploymentEnvs.replace('\n', '-')
            echo tagEnvs
            currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${tagEnvs}"
            echo currentBuild.displayName
          }
        }

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  if (continuousDeploymentEnvs == 'null') {
    nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                  qaEnvs, releaseVersion, organizationName, appGitRepoName, gitScm,
                  gitCommit, verifyFromChange)
  } else {
    qaApproveOrRollback(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                        qaEnvs, releaseVersion, organizationName, appGitRepoName, gitScm,
                        gitCommit, null, true, continuousDeploymentEnvs, verifyFromChange)
  }
}

def nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                  qaEnvs, releaseVersion, organizationName,
                  appGitRepoName, gitScm, gitCommit, verifyFromChange) {

  stage("Should I deploy to Non-Prod?") {
    checkpoint "Deploy To Non-Prod"

    def didAbort = false
    def didTimeout = false

    def userInput
    def deployEnv
    def releaseFlag

    currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"

    try {
        timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
          userInput = input(
            id: 'Proceed1', message: 'Approve Non-Prod Deploy?', parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Ready for Maven Release?', name: 'releaseFlag'],
              [$class: 'ChoiceParameterDefinition', choices: nonProdEnvs , description: 'Environments', name: 'env']
          ])
        }
        deployEnv = userInput['env']
        releaseFlag = userInput['releaseFlag']
    } catch(err) { // timeout reached or input false
      def user = err.getCauses()[0].getUser()
      if('SYSTEM' == user.toString()) { // SYSTEM means timeout.
        didTimeout = true
      } else {
        didAbort = true
        echo "Aborted by: [${user}]"
      }
    }

    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else if (didAbort) {
      // do something else
      echo "this was not successful"
      currentBuild.result = 'SUCCESS'
    } else {
      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}"
      nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                         qaEnvs, releaseVersion, organizationName, appGitRepoName, gitScm,
                         gitCommit, deployEnv, releaseFlag, verifyFromChange)
    }
  }
}

def nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                       qaEnvs, releaseVersion, organizationName, appGitRepoName, gitScm,
                       gitCommit, deployEnv, releaseFlag, verifyFromChange) {

  def commons = new com.westernasset.pipeline.Commons()

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}"

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"

  def tag;
  def nonProdDeployDisplayTag;
  def qaPassFlag

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sqitch', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
  ]) {
    node(POD_LABEL) {
      echo currentBuild.displayName
      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
      echo "qaPassFlag::::"
      if (qaPassFlag) {
        echo "true!!!"
      } else {
        echo "false!!!"
      }
      commons.snowflackScriptTokenReplacement(deployEnv);
      def hasTarget = commons.checkSqichTarget(deployEnv)
      def changeFrom = commons.snowflakeDeployStatus(organizationName, appGitRepoName, deployEnv, false, verifyFromChange, hasTarget)
      stage('Deploy') {
        commons.snowflakeDeploy(deployEnv, false, 'deploy', hasTarget)
      }
      stage('Verify') {
        commons.snowflakeDeploy(deployEnv, false, 'verify', changeFrom, hasTarget)
      }
    }
  }
  qaApproveOrRollback(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                      qaEnvs, releaseVersion, organizationName, appGitRepoName, gitScm,
                      gitCommit, deployEnv, qaPassFlag, deployEnv, verifyFromChange)
}

def qaApproveOrRollback(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                        qaEnvs, releaseVersion, organizationName, appGitRepoName, gitScm,
                        gitCommit, deployEnv, releaseFlag, continuousDeploymentEnvs, verifyFromChange) {

  def didAbort = false
  def didTimeout = false
  def userInput
  def crNumber
  def rollbackFlag

  def tagEnvs = continuousDeploymentEnvs.replace('\n', '-')
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${tagEnvs}"

  def commons = new com.westernasset.pipeline.Commons()

  stage("QA Approve or Rollback?") {
    checkpoint "QA Approve"
    try {
      if (releaseFlag) {
        timeout(time: 60, unit: 'SECONDS') {
          userInput = input(
            id: 'userInput', message: 'Rollback Change or Approve Release?', parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Rollback?', name: 'rollbackFlag'],
              [$class: 'TextParameterDefinition', defaultValue: '', description: 'CR Number', name: 'crNumber']
          ])
          crNumber = userInput['crNumber']
          println 'crNumber=' + crNumber

          rollbackFlag = userInput['rollbackFlag']
          println 'destroyFlag=' + rollbackFlag
        }
      } else {
        timeout(time: 60, unit: 'SECONDS') {
          userInput = input(
            id: 'userInput', message: 'Rollback Change?', parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Rollback?', name: 'rollbackFlag']
          ])

          println 'crNumber=' + crNumber

          rollbackFlag = userInput
          println 'destroyFlag=' + rollbackFlag
        }
      }
    } catch(err) { // timeout reached or input false
      println err
    }
  }

  if (didTimeout) {
    // do something on timeout
    echo "no input was received before timeout"
    currentBuild.result = 'SUCCESS'
  } else if (didAbort) {
    // do something else
    echo "this was not successful"
    currentBuild.result = 'SUCCESS'
  } else {
    if (rollbackFlag) {
      revertLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                  qaEnvs, releaseVersion, organizationName, appGitRepoName, gitScm,
                  gitCommit, deployEnv, releaseFlag, verifyFromChange)
    }
    if (crNumber != null) {
      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${tagEnvs}-${crNumber}"
      stage('trigger downstream job') {
        build job: "${env.opsReleaseJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
          [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
          [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(crNumber)],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
          [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
          [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
          [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
          [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
          [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)],
          [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
          [$class: 'StringParameterValue', name: 'verifyFromChange', value: String.valueOf(verifyFromChange)],
        ]
      }
    }
  }
}

def revertLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                qaEnvs, releaseVersion, organizationName, appGitRepoName, gitScm,
                gitCommit, deployEnv, releaseFlag, verifyFromChange) {

  def commons = new com.westernasset.pipeline.Commons()

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}"

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"

  def tag;
  def nonProdDeployDisplayTag;
  def qaPassFlag

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sqitch', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
  ]) {
    node(POD_LABEL) {
      echo currentBuild.displayName
      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      stage('Revert') {
        commons.snowflackScriptTokenReplacement(deployEnv);
        def hasTarget = commons.checkSqichTarget(deployEnv)
        commons.snowflakeDeployStatus(organizationName, appGitRepoName, deployEnv, false, verifyFromChange, hasTarget)
        commons.snowflakeDeploy(deployEnv, false, 'revert', hasTarget)
      }
    }
  }

}
