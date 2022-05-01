package com.aristotlecap.pipeline

import com.aristotlecap.pipeline.util.ConfluentUtil

def call(config) {
  currentBuild.displayName = "${config.branch_name}-${config.build_number}-${config.releaseVersion}"
  def commons = new com.aristotlecap.pipeline.Commons()
  def scriptRunner = new com.aristotlecap.pipeline.util.ConfluentUtil()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${config.builderTag}"
  def repo
  def qaBool = false
  def imqaBool = false
  def imuatBool = false
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

      def devBool = scriptRunner.isEnvAvailable('dev')
      def devEnv = (devBool)?'dev':'nonprod'
      def tagName = "${env.BRANCH_NAME}-${config.releaseVersion}-${devEnv}"
      println tagName
      def bool = true
      if (devBool) {
        try {
          sh """
            git rev-parse $tagName
          """
          bool = false
        } catch(ex) {}
      }
      if (bool) {
        stage ("Deploy to dev") {
          println 'nonprod environment ---->>>>>>>' + devEnv
          scriptRunner.processUserRequestForGivingEnv(config, devEnv, tagName, 'dev')
        }
        qaBool = scriptRunner.isEnvAvailable('qa')
        imqaBool = scriptRunner.isEnvAvailable('imqa')
        imuatBool = scriptRunner.isEnvAvailable('imuat')
        prodBool = scriptRunner.isEnvAvailable('prod')
        if (!qaBool && !prodBool && !imqaBool && !imuatBool) {
          //need to do the merge to master and remove the release branch
          scriptRunner.mergeToMaster(config.branch_name)
        }
      } else {
        error("The release tag is exist!")
      }
    }
  }
  def baseDisplayTag = "${config.branch_name}-${config.build_number}-${config.releaseVersion}"
  if (qaBool || imqaBool || imuatBool) {
    def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
    def gate = gateutil.gate(null, false, false, false, currentBuild.displayName, 'Ready for QA Deploy', 'Approve QA Deploy?')
    if (!gate.abortedOrTimeoutFlag) {
      qaConfluentDeployment(config, repo, env.BRANCH_NAME, baseDisplayTag)
    }
  }
  if (!qaBool && !imqaBool && !imuatBool && prodBool) {
    stage('Ready for QA Deploy'){}
    stage('Deploy to qa') {}
    continueToProd(baseDisplayTag, config, repo)
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
      def imqaTagName = "${branchName}-${config.releaseVersion}-imqa"
      def imuatTagName = "${branchName}-${config.releaseVersion}-imuat"
      println tagName
      qaBool = scriptRunner.isEnvAvailable('qa')
      imqaBool = scriptRunner.isEnvAvailable('imqa')
      imuatBool = scriptRunner.isEnvAvailable('imuat')
      def bool = true
      if (qaBool) {
        try {
          sh """
            git rev-parse $tagName
          """
          bool = false
        } catch(ex) {}
      }
      if (imqaBool) {
        try {
          sh """
            git rev-parse $imqaTagName
          """
          bool = false
        } catch(ex) {}
      }
      if (imuatBool) {
        try {
          sh """
            git rev-parse $imuatTagName
          """
          bool = false
        } catch(ex) {}
      }
      stage ("Deploy to qa") {
        if (bool) {
          scriptRunner.processUserRequestForGivingEnv(config, 'qa', tagName, 'qa')
          def imqaBool = scriptRunner.isEnvAvailable('imqa')
          def imuatBool = scriptRunner.isEnvAvailable('imuat')
          if (imqaBool) {
            scriptRunner.processUserRequestForGivingEnv(config, 'imqa', imqaTagName, 'imqa')
          }
          if (imuatBool) {
            scriptRunner.processUserRequestForGivingEnv(config, 'imuat', imuatTagName, 'imuat')
          }
        } else {
          error("The release tag is exist!")
        }
      }
      prodBool = scriptRunner.isEnvAvailable('prod')
      if (!prodBool) {
          //need to do the merge to master and remove the release branch
          scriptRunner.mergeToMaster(config.branch_name)
        }
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
