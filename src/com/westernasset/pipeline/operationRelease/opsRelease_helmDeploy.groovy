package com.westernasset.pipeline.operationRelease;

import groovy.util.GroovyCollections

def build(params) {
  build(params.gitScm, params.gitBranchName, params.gitCommit, params.prodEnv, params.drEnv,
          params.crNumber, params.organizationName, params.appGitRepoName,
          params.releaseVersion, params.buildNumber, params.appDtrRepo, params.projectType, params.templates,
          params.secrets, params.imageTags, params.postDeploySteps, params.dockerfileToTagMap, params.helmChartVersion)
}

def build(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
          crNumber, organizationName, appGitRepoName,
          releaseVersion, buildNumber, appDtrRepo, projectType, templates,
          secrets, imageTags, postDeploySteps, dockerfileToTagMap, helmChartVersion) {

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
    def userInput

    currentBuild.displayName = gitBranchName + '-' + releaseVersion + '-' + crNumber
    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Release?')
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }
    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      genericLogicImpl(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
                       crNumber, organizationName, appGitRepoName,
                       releaseVersion, buildNumber, appDtrRepo, projectType, templates,
                       secrets, imageTags, postDeploySteps, dockerfileToTagMap, helmChartVersion)
    }
  }
}

def genericLogicImpl(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
                    crNumber, organizationName, appGitRepoName,
                    releaseVersion, buildNumber, appDtrRepo, projectType, templates,
                    secrets, imageTags, postDeploySteps, dockerfileToTagMap, helmChartVersion) {

  def liquibaseImage = "${env.TOOL_BUSYBOX}"

  def deploymentFilename
  def upstreamJobName
  def upstreamBuildNumber
  def deploymentName

  def commons = new com.westernasset.pipeline.Commons()
  def helm = new com.westernasset.pipeline.util.HelmUtil()
  def prodCloud = commons.getProdCluster(prodEnv);
  podTemplate(
    cloud: "${prodCloud}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'helm', image: "${env.TOOL_HELM}", ttyEnabled: true),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true)
  ]) {
    node(POD_LABEL) {
      echo prodEnv
      echo crNumber
      echo "deploy to ---> ${prodEnv}"
      echo releaseVersion
      echo imageTags

      def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
      echo workspace

      def secretRoot = "secret/${organizationName}/${appGitRepoName}/prod"
      println secretRoot

      def appRoleName = organizationName + '-' + appGitRepoName + '-prod'
      println appRoleName

      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        stage("Deploy to PROD") {
          currentBuild.displayName = gitBranchName + '-' + releaseVersion + '-' + crNumber + '-' + prodEnv

          echo buildNumber

          def releaseImageTag = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
          echo releaseImageTag

          //construct the map for security
          def secretsMap = [templates.split(),secrets.split()].transpose().collectEntries{[it[0],it[1]]}
          print secretsMap

          helm.deploy(workspace, releaseImageTag, "${env.IMAGE_REPO_URI}", secretRoot, appRoleName,
                      organizationName, appGitRepoName, prodEnv, secretsMap,
                      true, false, dockerfileToTagMap, gitBranchName, buildNumber,
                      gitCommit, crNumber, helmChartVersion)

        }
  	  } catch (err) {
  	    currentBuild.result = 'FAILED'
  	    throw err
  	  }
    }
  }

}
