package com.aristotlecap.pipeline

import com.aristotlecap.pipeline.util.ConfluentConnectorUtil
import com.aristotlecap.pipeline.steps.*

def call(config) {
  currentBuild.displayName = "${config.branch_name}-${config.build_number}"
  def commons = new com.aristotlecap.pipeline.Commons()
  def connector = new com.aristotlecap.pipeline.util.ConfluentConnectorUtil()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${config.builderTag}"
  def baseDisplayTag = currentBuild.displayName
  def prompt = new Prompt()
  def repo

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: env.TOOL_VAULT, ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
  ]) {
    node(POD_LABEL) {
      repo = commons.clone()
      deleteDir()
      git url: repo.gitScm, credentialsId: 'ghe-jenkins', branch: env.BRANCH_NAME

      stage ("Deploy to NonProd") {
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
            connector.deploy(config, repo, 'dev', false)
          } catch(e) {
            e.printStackTrace()
            error(e.getMessage())
          }
        }
      }
    }
  }

  def nonProdEnvs = ["qa", "uat"]
  def qaEnvs = []
  def environment = prompt.nonprod(nonProdEnvs, qaEnvs);
  if (environment == null || environment.isEmpty()) {
    return // Stop if user aborts or timeout
  }

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: env.TOOL_VAULT, ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
  ]) {
    node(POD_LABEL) {
      repo = commons.clone()
      deleteDir()
      git url: repo.gitScm, credentialsId: 'ghe-jenkins', branch: env.BRANCH_NAME

      stage ("Deploy to NonProd") {
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
            connector.deploy(config, repo, environment, false)
          } catch(e) {
            e.printStackTrace()
            error(e.getMessage())
          }
        }
      }
    }
  }

  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, true, false, "${baseDisplayTag}", 'Approve Release?')
  def crNumber = gate.crNumber
  if (crNumber != null) {
    currentBuild.displayName = "${baseDisplayTag}-${crNumber}"

    def strArray = []
    config.connectors.each { it ->
      def str = connector.getStringFromMap(it, ":")
      strArray.push(str)
    }
    def connectorsString = strArray.join("\n")
    println 'connectorsString->' + connectorsString

    stage('trigger downstream job') {
      build job: "${env.opsReleaseJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'projectType', value: "confluentConnector"],
        [$class: 'StringParameterValue', name: 'buildNumber', value: config.build_number],
        [$class: 'StringParameterValue', name: 'crNumber', value: gate.crNumber],
        [$class: 'StringParameterValue', name: 'organizationName', value: repo.organizationName],
        [$class: 'StringParameterValue', name: 'appGitRepoName', value: repo.appGitRepoName],
        [$class: 'StringParameterValue', name: 'gitScm', value: repo.gitScm],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: config.branch_name],
        [$class: 'StringParameterValue', name: 'connectors', value: connectorsString],
        [$class: 'StringParameterValue', name: 'builderTag', value: config.builderTag],
      ]
    }
  }
}
