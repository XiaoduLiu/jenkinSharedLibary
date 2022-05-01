package com.aristotlecap.pipeline.operationRelease

import com.aristotlecap.pipeline.util.ConfluentUtil

def build(buildNumber, crNumber, gitBranchName, gitCommit, gitScm, releaseVersion, builderTag, scriptRoot) {
  def didTimeout

  println buildNumber
  println crNumber
  println gitBranchName
  println gitCommit
  println gitScm
  println releaseVersion
  println builderTag

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    didTimeout = false
    def userInput

    currentBuild.displayName = "${gitBranchName}-${buildNumber}-${releaseVersion}-${crNumber}"

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Release?')
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }
  }
  if (didTimeout) {
    // do something on timeout
    echo "no input was received before timeout"
    currentBuild.result = 'SUCCESS'
  } else {
    deploy(buildNumber, crNumber, gitBranchName, gitCommit, gitScm, releaseVersion, builderTag, scriptRoot)
  }
}

def deploy(buildNumber, crNumber, gitBranchName, gitCommit, gitScm, releaseVersion, builderTag, scriptRoot) {
  currentBuild.displayName = "${gitBranchName}-${buildNumber}-${releaseVersion}-${crNumber}"
  def commons = new com.aristotlecap.pipeline.Commons()
  def scriptRunner = new com.aristotlecap.pipeline.util.ConfluentUtil()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  def repo
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat')],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh')
  ]){
    node(POD_LABEL) {

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"

      def tagName = "${gitBranchName}-${releaseVersion}-prod"
      println tagName
      def bool = true

      def config=[:]
      config.branch_name = gitBranchName
      config.build_number = buildNumber
      config.builderTag = builderTag
      config.scriptRoot = scriptRoot
      config.releaseVersion=releaseVersion

      try {
        sh """
          git rev-parse $tagName
        """
        bool = false
      } catch(ex) {}
      if (bool) {
        scriptRunner.processUserRequestForGivingEnv(config, 'prod', tagName, 'prod')
        scriptRunner.mergeToMaster(gitBranchName)
      } else {
        error("The release tag is exist!")
      }
    }
  }
}
