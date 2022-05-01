package com.aristotlecap.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          qaEnvs, prodEnv, drEnv, releaseVersion, tdsxFiles,
          tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
          twbProjects, twbSecrets, deleteNames, deleteFromProjects ) {

  def repo
  def commons = new com.aristotlecap.pipeline.Commons()

  echo builderTag
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"

  podTemplate(
    cloud: 'pas-development',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}')
    ],
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins')
  {
    node(POD_LABEL) {
      repo = commons.clone()
    }
  }
  nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                qaEnvs, prodEnv, drEnv, releaseVersion, tdsxFiles,
                tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
                twbProjects, twbSecrets, deleteNames, deleteFromProjects,
                repo.organizationName, repo.appGitRepoName, repo.gitScm, repo.gitCommit)
}

def nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                  qaEnvs, prodEnv, drEnv, releaseVersion, tdsxFiles,
                  tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
                  twbProjects,twbSecrets, deleteNames, deleteFromProjects,
                  organizationName, appGitRepoName, gitScm, gitCommit) {

  stage("Should I deploy to Non-Prod?") {
    checkpoint "Deploy To Non-Prod"

    def didAbort = false
    def didTimeout = false

    def userInput
    def deployEnv
    def releaseFlag
    def tabbedFlag

    currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"

    try {
        timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
          userInput = input(
            id: 'Proceed1', message: 'Approve Non-Prod Deploy?', parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Ready for Maven Release?', name: 'releaseFlag'],
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Add tabbed extension?', name: 'tabbedFlag'],
              [$class: 'ChoiceParameterDefinition', choices: nonProdEnvs , description: 'Environments', name: 'env']
          ])
        }
        deployEnv = userInput['env']
        releaseFlag = userInput['releaseFlag']
        tabbedFlag = userInput['tabbedFlag']
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
                         qaEnvs, prodEnv, drEnv, releaseVersion, tdsxFiles,
                         tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
                         twbProjects, twbSecrets, deleteNames, deleteFromProjects,
                         organizationName, appGitRepoName, gitScm, gitCommit,
                         deployEnv, releaseFlag, tabbedFlag)
    }
  }
}

def nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
                       qaEnvs, prodEnv, drEnv, releaseVersion, tdsxFiles,
                       tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
                       twbProjects, twbSecrets, deleteNames, deleteFromProjects,
                       organizationName, appGitRepoName, gitScm, gitCommit,
                       deployEnv, releaseFlag, tabbedFlag) {

  def commons = new com.aristotlecap.pipeline.Commons()

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
      containerTemplate(name: 'tableau', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
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

      stage('Deploy to Non-Prod') {
        commons.processTableauResource(tdsxFiles, tdsxNames, tdsxSecrets, tdsxProjects, 'tdsx', deployEnv, tabbedFlag, organizationName, appGitRepoName)
        commons.processTableauResource(twbFiles, twbNames, twbSecrets, twbProjects, 'twb', deployEnv, tabbedFlag, organizationName, appGitRepoName)
        try {
          commons.processTableauResource('null', deleteNames, 'null', deleteFromProjects, 'delete', deployEnv, 'null', organizationName, appGitRepoName)
        } catch(e) {
          println 'failed on delete in nonprod, allow it pass and move forward to prod delete....'
        }
      }
    }
  }
  if (qaPassFlag) {
    qaApprove(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
              qaEnvs, prodEnv, drEnv, releaseVersion, tdsxFiles,
              tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
              twbProjects, twbSecrets, deleteNames, deleteFromProjects,
              organizationName, appGitRepoName, gitScm, gitCommit,
              deployEnv, releaseFlag, tabbedFlag)
  }
}

def qaApprove(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
              qaEnvs, prodEnv, drEnv, releaseVersion, tdsxFiles,
              tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
              twbProjects, twbSecrets, deleteNames, deleteFromProjects,
              organizationName, appGitRepoName, gitScm, gitCommit,
              deployEnv, releaseFlag, tabbedFlag) {

  def didAbort = false
  def didTimeout = false
  def userInput

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}"

  stage("QA Approve?") {
    checkpoint "QA Approve"
    currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}"
    try {
      timeout(time: 60, unit: 'SECONDS') {
        userInput = input(
          id: 'userInput', message: 'Approve Release?', parameters: [
            [$class: 'TextParameterDefinition', defaultValue: '', description: 'CR Number', name: 'crNumber']
        ])
        echo ("CR Number: "+userInput)
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
    if (userInput != null) {
      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}-${userInput}"
      stage('trigger downstream job') {
        build job: "${env.opsReleaseJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
          [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
          [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(userInput)],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
          [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
          [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
          [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
          [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
          [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)],
          [$class: 'StringParameterValue', name: 'tdsxFiles', value: String.valueOf(tdsxFiles)],
          [$class: 'StringParameterValue', name: 'tdsxNames', value: String.valueOf(tdsxNames)],
          [$class: 'StringParameterValue', name: 'tdsxProjects', value: String.valueOf(tdsxProjects)],
          [$class: 'StringParameterValue', name: 'twbFiles', value: String.valueOf(twbFiles)],
          [$class: 'StringParameterValue', name: 'twbNames', value: String.valueOf(twbNames)],
          [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
          [$class: 'StringParameterValue', name: 'twbProjects', value: String.valueOf(twbProjects)],
          [$class: 'StringParameterValue', name: 'tabbedFlag', value: String.valueOf(tabbedFlag)],
          [$class: 'StringParameterValue', name: 'tdsxSecrets', value: String.valueOf(tdsxSecrets)],
          [$class: 'StringParameterValue', name: 'twbSecrets', value: String.valueOf(twbSecrets)],
          [$class: 'StringParameterValue', name: 'deleteNames', value: String.valueOf(deleteNames)],
          [$class: 'StringParameterValue', name: 'deleteFromProjects', value: String.valueOf(deleteFromProjects)],

        ]
      }
    }
  }
}
