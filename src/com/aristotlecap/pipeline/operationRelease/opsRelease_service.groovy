package com.aristotlecap.pipeline.operationRelease;

def build(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
          crNumber, liquibaseChangeLog, liquibaseBuilderTag, organizationName, appGitRepoName,
          releaseVersion, buildNumber, appDtrRepo, projectType, templates,
          secrets, imageTags, postDeploySteps, dockerfileToTagMap) {

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
    def userInput

    currentBuild.displayName = releaseVersion + '-' + crNumber
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
                       crNumber, liquibaseChangeLog, liquibaseBuilderTag, organizationName, appGitRepoName,
                       releaseVersion, buildNumber, appDtrRepo, projectType, templates,
                       secrets, imageTags, postDeploySteps, dockerfileToTagMap)
    }
  }
}

def genericLogicImpl(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
                    crNumber, liquibaseChangeLog, liquibaseBuilderTag, organizationName, appGitRepoName,
                    releaseVersion, buildNumber, appDtrRepo, projectType, templates,
                    secrets, imageTags, postDeploySteps, dockerfileToTagMap) {

  def liquibaseImage = "${env.TOOL_BUSYBOX}"
  if (liquibaseBuilderTag != null && liquibaseBuilderTag != 'null') {
    liquibaseImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${liquibaseBuilderTag}"
  }

  def deploymentFilename
  def upstreamJobName
  def upstreamBuildNumber
  def deploymentName

  def commons = new com.aristotlecap.pipeline.Commons()
  def prodCloud = commons.getProdCluster(prodEnv);
  podTemplate(
    cloud: "${prodCloud}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'liquibase', image: "${liquibaseImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
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

        if (liquibaseChangeLog != 'null') {
          commons.liquibaseProcess("${env.liquibaseProjectFolder}", workspace, liquibaseChangeLog, secretRoot, appRoleName,
                                   true, null, projectType)
        }

        stage("Deploy to PROD") {
          currentBuild.displayName = releaseVersion + '-' + crNumber + '-' + prodEnv

          echo buildNumber

          def releaseImageTag = "${releaseVersion}-${crNumber}"
          echo releaseImageTag

          deploymentName = commons.deploy(workspace, releaseImageTag, "${env.IMAGE_REPO_URI}", secretRoot, appRoleName,
                                              organizationName, appGitRepoName, prodEnv, templates, secrets,
                                              true, false, dockerfileToTagMap, gitBranchName, buildNumber,
                                              gitCommit, crNumber)

          commons.postDeployStepsLogic(postDeploySteps, deploymentName)

          deploymentFilename = commons.findDeploymentFileName()
          upstreamJobName = "${env.JOB_NAME}"
          upstreamBuildNumber ="${env.BUILD_NUMBER}"

        }
  	  } catch (err) {
  	    currentBuild.result = 'FAILED'
  	    throw err
  	  }
    }
  }

  build job: "${env.deploymentYAMLCollectorJob}", wait: false, parameters: [
    [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
    [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
    [$class: 'StringParameterValue', name: 'deploymentFilename', value: String.valueOf(deploymentFilename)],
    [$class: 'StringParameterValue', name: 'deploymentName', value: String.valueOf(deploymentName)],
    [$class: 'StringParameterValue', name: 'upstreamJobName', value: String.valueOf(upstreamJobName)],
    [$class: 'StringParameterValue', name: 'upstreamBuildNumber', value: String.valueOf(upstreamBuildNumber)]
  ]

  def drCloud = commons.getProdCluster(drEnv);
  podTemplate(
    cloud: "${drCloud}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true)
  ]) {
    node(POD_LABEL) {
      echo prodEnv
      echo crNumber
      echo "Deploy to ---> ${drEnv}"
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

        stage("Deploy to DR") {
          currentBuild.displayName = releaseVersion + '-' + crNumber + '-' + prodEnv

          echo buildNumber

          def releaseImageTag = "${releaseVersion}-${crNumber}"
          echo releaseImageTag

          commons.deploy(workspace, releaseImageTag, "${env.IMAGE_REPO_URI}", secretRoot, appRoleName,
                         organizationName, appGitRepoName, prodEnv, templates, secrets,
                         true, true, dockerfileToTagMap, gitBranchName, buildNumber,
                         gitCommit, crNumber)

        }
  	  } catch (err) {
  	    currentBuild.result = 'FAILED'
  	    throw err
  	  }
    }
  }
}
