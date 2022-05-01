package com.aristotlecap.pipeline.operationRelease

import com.aristotlecap.pipeline.util.ConfluentUtil

def build(builderTag, buildNumber, crNumber, organizationName, appGitRepoName, gitScm, requesterId) {
  def didTimeout

  println buildNumber
  println crNumber
  println appGitRepoName
  println gitScm
  println requesterId

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    didTimeout = false
    def userInput

    currentBuild.displayName = "master-${buildNumber}-${requesterId}-${crNumber}"

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
    deploy(builderTag, buildNumber, crNumber, organizationName, appGitRepoName, gitScm, requesterId)
  }
}

def deploy(builderTag, buildNumber, crNumber, organizationName, appGitRepoName, gitScm, requesterId) {
  currentBuild.displayName = "master-${buildNumber}-${requesterId}-${crNumber}"
  def commons = new com.aristotlecap.pipeline.Commons()
  def sftp = new com.aristotlecap.pipeline.util.AwsSftpTransferUsersUtil()
  def repo
  def cdkBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'cdk', image: "${cdkBuilderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ]){
    node(POD_LABEL) {
      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "master"

      //load requester file
      def requesterJson = [:]
      if (fileExists("${workspace}/${requesterId}.request")) {
        echo "Yes, ${workspace}/${requesterId}.request exists"
        requesterJson = readJSON file: "${workspace}/${requesterId}.request"
        println requesterJson
      }
      def createList = requesterJson['create']
      def deleteList = requesterJson['delete']

      def releaseTagName = "master-${buildNumber}"
      stage("Deploy to prod") {
        container('cdk') {
          commons.setNpmrcFilelink()
        }
        //process create list
        for (String userName : createList) {
          println "starting create user account : ${userName} ..."
          //vault processing
          def json = sftp.vaultProcess(true, organizationName, appGitRepoName, userName, null)
          //cdk processing
          sftp.cdkProcessing(userName, json)
          sftp.createDefaultFolders(userName)
          //add the user to the local user list
          sftp.addUserToLocalUserList(userName, organizationName, appGitRepoName)
          println "done create user account : ${userName}!"
          releaseTagName = releaseTagName + "-create-" + userName
        }

        //process delete list
        for (String deleteUser: deleteList) {
          println "starting delete user account : ${deleteUser} ..."
          def userJson = sftp.vaultProcessForDelete(organizationName, appGitRepoName, deleteUser)
          sftp.cdkDeleteProcessing(deleteUser, userJson)
          sftp.vaultDelete(organizationName, appGitRepoName, deleteUser)
          sftp.removeUserFromLocalList(deleteUser)
          println "done delete user account : ${deleteUser}"
          releaseTagName = releaseTagName + "-delete-" + deleteUser
        }
      }

      stage("Git Release") {
        sftp.moveProcessingFile(requesterId)
        println 'release tag name :' + releaseTagName
        sftp.commitRelease(releaseTagName)
      }
    }
  }
}
