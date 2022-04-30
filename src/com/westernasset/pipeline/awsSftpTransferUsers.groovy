package com.westernasset.pipeline

import com.westernasset.pipeline.util.AwsSftpTransferUsersUtil

def call(config) {
  currentBuild.displayName = "master-${config.build_number}"
  def commons = new com.westernasset.pipeline.Commons()
  def sftp = new com.westernasset.pipeline.util.AwsSftpTransferUsersUtil()
  def baseDisplayTag
  def requesterId
  String gitRemoteURL
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'vault', image: env.TOOL_VAULT, ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
  ]) {
    node(POD_LABEL) {
      stage ('Clone') {
        // Clean workspace before doing anything
        deleteDir()
        checkout scm
        sh """
          git checkout master
          git pull --all
          git branch
         """
      }

      gitRemoteURL = sh(label: 'Get Git remote URL', returnStdout: true, script: "git config --get remote.origin.url").trim()
      String shortName = gitRemoteURL.drop(28).reverse().drop(4).reverse()
      def names = shortName.split('/')
      organizationName = names[0]
      appGitRepoName = names[1]

      println organizationName
      println appGitRepoName

      //echo sh(script: 'env|sort', returnStdout: true)
      println config

      //find out the requestor and request login will be used as the name for the user branch
      wrap([$class: 'BuildUser']) {
        requesterId = "${BUILD_USER_ID}"
      }
      print requesterId
      currentBuild.displayName = "master-${config.build_number}-${requesterId}"
      baseDisplayTag = currentBuild.displayName

      //check the input
      sftp.checkInput(config)

      //get the action
      def action = config.Action.toLowerCase()
      def userName = config.User_Account_Login_Name

      if (action == 'none') {
        def requestFileJson = sftp.getRequestFileJson(requesterId, action);
        if (requestFileJson == null) {
          error('There is no changes, please start to add changes to the list')
        }
        stage ('Store Request for Review') {}

      } else {
        stage ('Store Request for Review') {

          //check if action is valid
          sftp.checkClientExist(action, userName)

          //get the request Json object
          def requestFileJson = sftp.getRequestFileJson(requesterId, action);

          //process request will store the request to vault nonprod side for review
          //and generate the process control record for this request
          sftp.processRequest(requesterId, requestFileJson, config, organizationName, appGitRepoName)

        }
      }
    }
  }
  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, true, false, "${baseDisplayTag}", 'Approve Release?')
  def crNumber = gate.crNumber
  if (crNumber != null) {
    currentBuild.displayName = "${baseDisplayTag}-${crNumber}"
    stage('trigger downstream job') {
      build job: "${env.opsReleaseJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'projectType', value: "awsSftpTransferUsers"],
        [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(config.build_number)],
        [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(gate.crNumber)],
        [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
        [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
        [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitRemoteURL)],
        [$class: 'StringParameterValue', name: 'requesterId', value: String.valueOf(requesterId)],
        [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(config.builderTag)],
      ]
    }
  }
}
