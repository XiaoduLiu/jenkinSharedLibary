package com.aristotlecap.pipeline

import com.aristotlecap.pipeline.util.ConfluentConnectorUtil

def call(config) {
  currentBuild.displayName = "${config.branch_name}-${config.build_number}"
  def commons = new com.aristotlecap.pipeline.Commons()
  def connector = new com.aristotlecap.pipeline.util.ConfluentConnectorUtil()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${config.builderTag}"
  def baseDisplayTag = currentBuild.displayName
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
          try {
            connector.deploy(config, repo, false)
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
