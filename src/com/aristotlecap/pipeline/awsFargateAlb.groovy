package com.aristotlecap.pipeline;

import com.aristotlecap.pipeline.util.*

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, buildSteps,
          nonProdEnvs, qaEnvs, releaseVersion, budgetCode) {

  def commons = new com.aristotlecap.pipeline.Commons()
  def fargate = new com.aristotlecap.pipeline.util.FargateUtil()

  def builderImage = "${env.TOOL_BUSYBOX}"
  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  }
  echo builderTag
  def repo
  def imageTag
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ])  {
    node(POD_LABEL) {
      currentBuild.displayName = "${gitBranchName}-${buildNumber}"
      try {
        repo = commons.clone()
        commons.setNpmrcFilelink()
        imageTag = "${env.IMAGE_REPO_URI}\\/${env.IMAGE_REPO_NONPROD_KEY}\\/${organizationName}\\/${appGitRepoName}:${gitBranchName}-${releaseVersion}-${buildNumber}"
        stage('Code Build') {
          fargate.localBuild(builderTag, buildSteps)
        }
        stage('Docker Build') {
          fargate.dockerBuild("${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_NONPROD_KEY}", releaseVersion, repo.organizationName, repo.appGitRepoName, repo.gitBranchName, repo.gitCommit, buildNumber)
        }
        //echo sh(script: 'env|sort', returnStdout: true)
        currentBuild.displayName  = "${gitBranchName}-${releaseVersion}-${buildNumber}"
      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonProdEnvs, true, false, false, currentBuild.displayName)
  if (!gate.abortedOrTimeoutFlag) {
    deployNonprodResource(projectTypeParam, gitBranchName, buildNumber, repo.organizationName, repo.appGitRepoName,
                          repo.gitScm, repo.gitCommit, builderTag, nonProdEnvs, qaEnvs,
                          releaseVersion, budgetCode, gate.deployEnv, gate.releaseFlag, imageTag)
  }
}

def deployNonprodResource(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                          gitScm, gitCommit, builderTag, nonProdEnvs, qaEnvs,
                          releaseVersion, budgetCode, deployEnv, releaseFlag, imageTag) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def fargate = new com.aristotlecap.pipeline.util.FargateUtil()
  def builderImage = "${env.TOOL_BUSYBOX}"
  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  }
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'cdk', image: "${env.TOOL_CDK}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws'),
        persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
        persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ]) {
    node(POD_LABEL) {
      try {
        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"
        commons.setNpmrcFilelink()
        stage('Deploy To Non-Prod') {
          fargate.awsDeployment(deployEnv, projectTypeParam, organizationName, appGitRepoName, budgetCode, imageTag, false)
        }
      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  qaPassFlag = fargate.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
  if (qaPassFlag) {
    def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
    def gate = gateutil.gate(null, false, true, false, currentBuild.displayName, 'Ready to Release?', 'Approve Release?')
    if (gate.crNumber != null) {
      qaApprove(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                gitScm, gitCommit, builderTag, nonProdEnvs, qaEnvs,
                releaseVersion, budgetCode, deployEnv, releaseFlag, imageTag, gate.crNumber)
    }
  }
}

def qaApprove(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
              gitScm, gitCommit, builderTag, nonProdEnvs, qaEnvs,
              releaseVersion, budgetCode, deployEnv, releaseFlag, imageTag, crNumber) {

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
  def fargate = new com.aristotlecap.pipeline.util.FargateUtil()
  def commons = new com.aristotlecap.pipeline.Commons()

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
  ]) {

    node(POD_LABEL) {
      def image = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${organizationName}/${appGitRepoName}:${gitBranchName}-${releaseVersion}-${buildNumber}"
      println image
      def crTag = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}-${crNumber}"
      println crTag

      println 'need to push this crTag to non prod'
      def approveImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${organizationName}/${appGitRepoName}:${crTag}"
      println  approveImage

      def repoNameLower = appGitRepoName.toLowerCase().replace('.', '-')

      String liveTag1 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${gitBranchName}-${releaseVersion}-${buildNumber}"
      String liveTag2 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${gitBranchName}-${releaseVersion}--latest"
      String liveTag3 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

      echo "liveTag1 -> ${liveTag1}"
      echo "liveTag2 -> ${liveTag2}"
      echo "liveTag3 -> ${liveTag3}"

      fargate.pushImageToProdDtr(image, approveImage)
      fargate.pushImageToProdDtr(image, liveTag1)
      fargate.pushImageToProdDtr(image, liveTag2)
      fargate.pushImageToProdDtr(image, liveTag3)

      stage('trigger downstream job') {
        build job: "${env.opsReleaseJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
          [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
          [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(crNumber)],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
          [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
          [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
          [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
          [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
          [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)],
          [$class: 'StringParameterValue', name: 'budgetCode', value: String.valueOf(budgetCode)]
        ]
      }
    }
  }
}
