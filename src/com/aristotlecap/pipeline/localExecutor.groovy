package com.aristotlecap.pipeline

import com.aristotlecap.pipeline.util.ConfluentUtil

def call(config) {
  currentBuild.displayName = "${config.branch_name}-${config.build_number}-${config.releaseVersion}"
  def commons = new com.aristotlecap.pipeline.Commons()
  def scriptRunner = new com.aristotlecap.pipeline.util.ConfluentUtil()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${config.builderTag}"
  def repo
  def qaBool = false
  def prodBool = false
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: env.TOOL_AGENT, args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat')],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
  ]){
    node(POD_LABEL) {
      repo = commons.clone()
      deleteDir()
      git url: repo.gitScm, credentialsId: 'ghe-jenkins', branch: env.BRANCH_NAME

        scriptRunner.processUserRequestForGivingEnv(config, devEnv, tagName, 'dev')
        if (!qaBool && !prodBool) {
          //need to do the merge to master and remove the release branch
          scriptRunner.mergeToMaster(config.branch_name)
        }
    }
  }
}

def qaConfluentDeployment(config, repo, branchName, baseDisplayTag) {
  currentBuild.displayName = currentBuild.displayName + "-qa"
  def commons = new com.aristotlecap.pipeline.Commons()
  def scriptRunner = new com.aristotlecap.pipeline.util.ConfluentUtil()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${config.builderTag}"
  def prodBool = false
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat')],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
  ]){
    node(POD_LABEL) {
      deleteDir()
      git url: repo.gitScm, credentialsId: 'ghe-jenkins', branch: branchName

      def tagName = "${branchName}-${config.releaseVersion}-qa"
      println tagName
      def bool = true
      try {
        sh """
          git rev-parse $tagName
        """
        bool = false
      } catch(ex) {}
      if (bool) {
        scriptRunner.processUserRequestForGivingEnv(config, 'qa', tagName, 'qa')
      } else {
        error("The release tag is exist!")
      }
      prodBool = scriptRunner.isEnvAvailable('prod')
    }
  }
  if (prodBool) {
    continueToProd(baseDisplayTag, config, repo)
  }
}

def continueToProd(baseDisplayTag, config, repo) {
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, true, false, "${baseDisplayTag}-dev-qa", 'Approve Release?')
  def crNumber = gate.crNumber
  if (crNumber != null) {
    currentBuild.displayName = "${baseDisplayTag}-dev-qa-${crNumber}"
    stage('trigger downstream job') {
      build job: "${env.opsReleaseJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'projectType', value: "scriptExecutor"],
        [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf("${config.build_number}")],
        [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf("${gate.crNumber}")],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf("${config.branch_name}")],
        [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf("${repo.gitCommit}")],
        [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf("${repo.gitScm}")],
        [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf("${config.releaseVersion}")],
        [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf("${config.builderTag}")],
        [$class: 'StringParameterValue', name: 'scriptRoot', value: String.valueOf("${config.scriptRoot}")]
      ]
    }
  }
}
