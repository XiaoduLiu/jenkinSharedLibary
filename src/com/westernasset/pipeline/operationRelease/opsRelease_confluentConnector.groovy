package com.westernasset.pipeline.operationRelease

import com.westernasset.pipeline.util.ConfluentUtil

def build(buildNumber, crNumber, gitBranchName, gitScm,
          connectors, builderTag,  organizationName, appGitRepoName) {

  def didTimeout

  println buildNumber
  println crNumber
  println gitBranchName
  println gitScm
  println organizationName
  println appGitRepoName
  println connectors
  println builderTag

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    didTimeout = false
    def userInput

    currentBuild.displayName = "${gitBranchName}-${buildNumber}-${crNumber}"

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
    deploy(buildNumber, crNumber, gitBranchName, gitScm,
           connectors, builderTag,  organizationName, appGitRepoName)
  }
}

def deploy(buildNumber, crNumber, gitBranchName, gitScm,
           connectors, builderTag,  organizationName, appGitRepoName) {
  currentBuild.displayName = "${gitBranchName}-${buildNumber}-${crNumber}"
  def commons = new com.westernasset.pipeline.Commons()
  def connector = new com.westernasset.pipeline.util.ConfluentConnectorUtil()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  podTemplate(
    cloud: "sc-production",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-prod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"

      def config=[:]
      config.branch_name = gitBranchName
      config.build_number = buildNumber
      config.builderTag = builderTag

      def strArray = connectors.split("\n")
      def arr = []
      def connectionNames = ''
      strArray.each{ me ->
        println "my map string->" + me
        def obj = connector.getMapFromString(me, ":")
        connectionNames = connectionNames + "-" + obj.connectorName
        arr.push(obj)
      }
      config.connectors = arr

      def tagName = "${gitBranchName}${connectionNames}"
      println tagName

      println config

      def repo=[:]
      repo.organizationName = organizationName
      repo.appGitRepoName = appGitRepoName

      println repo

      stage("Deploy to Prod") {
        echo "EXECUTE PROD DEPLOY"
        container('builder') {
          withCredentials([usernamePassword(credentialsId: "${env.CCLOUD_ADMIN}", usernameVariable: 'ccloud_email', passwordVariable: 'ccloud_password')]) {
            //echo sh(script: 'env|sort', returnStdout: true)
            sh """
              pwd
              cat $workspace/scripts/ccloud_login.sh
              chmod +x $workspace/scripts/ccloud_login.sh
              $workspace/scripts/ccloud_login.sh $ccloud_email '$ccloud_password'
            """
          }
          try {
            connector.deploy(config, repo, 'prod', true)
            //tag the github
            connector.mergeToMaster(gitBranchName)
            connector.commitRelease(tagName)
          } catch(e) {
            e.printStackTrace()
            error(e.getMessage())
          }
        }
      }
    }
  }
}
