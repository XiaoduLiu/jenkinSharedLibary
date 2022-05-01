package com.aristotlecap.pipeline

import com.aristotlecap.pipeline.util.HelmUtil

import com.aristotlecap.pipeline.steps.Hadolint

def call(config) {
  currentBuild.displayName = "${config.branch_name}-${config.build_number}-${config.releaseVersion}"
  def commons = new com.aristotlecap.pipeline.Commons()
  def hadolint = new Hadolint()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${config.builderTag}"
  def repo
  def qaBool = false
  def prodBool = false
  def imageTag
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: env.TOOL_AGENT, args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      hadolint.containerTemplate()
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]){
    node(POD_LABEL) {
      repo = commons.clone()

      try {
        if (fileExists("${workspace}/Dockerfile")) {
          echo 'Yes, there is a Dockerfile exist at the project root and there is a docker build'
          iimageTag = commons.setJobLabelNonJavaProject(config.branch_name, repo.gitCommit, config.build_number, config.releaseVersion)
          config.dockerfiles.each{ dockerFile, tag ->
            def dockerfilefullpath = "${workspace}/${dockerFile}"
            hadolint.lint()
            commons.dockerBuildForMultiImages(env.IMAGE_REPO_URI, env.IMAGE_REPO_NONPROD_KEY, repo.appDtrRepo,
                                              tag, repo.organizationName, repo.appGitRepoName,
                                              config.branch_name, repo.gitCommit, dockerfilefullpath,
                                              config.build_number, 'Docker Build')
          }
        } else {
          echo 'No, there is no DockerFile exist at the project root and there is no docker build'
        }
      } catch(e) {
        println e.getMessage()
        currentBuild.result = 'FAILED'
        throw e
      }
    }
  }
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(config.nonProdEnvs, true, false, false, currentBuild.displayName, "Should I deploy to Non-Prod?", "Approve Non-Prod Deploy?", "Ready for Release")
  if (gate.deployEnv != null) {
    nonProdDeploy(gate.deployEnv, gate.releaseFlag, repo, config, imageTag)
  }
}

def nonProdDeploy(selectedEnv, releaseFlag, repo, config, imageTag) {

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${config.builderTag}"

  def commons = new com.aristotlecap.pipeline.Commons()
  def helmUtil = new com.aristotlecap.pipeline.util.HelmUtil()
  def envCluster = commons.getNonProdEnvDetailsForService(selectedEnv)
  def deployEnv = envCluster.deployEnv
  def clusterName = (envCluster.clusterName != null)? envCluster.clusterName: 'pas-development'

  podTemplate(
    cloud: "${clusterName}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {

      echo currentBuild.displayName
      deleteDir()
      checkout scm
      sh "git reset --hard ${repo.gitCommit}"

      currentBuild.displayName = "${config.branch_name}-${config.build_number}-${config.releaseVersion}-${deployEnv}"

      stage("Deploy to Non-Prod") {
        echo "EXECUTE DEV DEPLOY"
        helmUtil.updateHelmRepos(config)
        helmUtil.helmDeploy(config, repo, deployEnv, false)
      }
    }
  }
  def qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, helmUtil.getJoinList(config.qaEnvs))
  echo "qaPassFlag::::"
  if (qaPassFlag) {
    echo "true!!!"
  } else {
    echo "false!!!"
  }
  if (qaPassFlag) {
    def baseDisplayTag = "${config.branch_name}-${config.build_number}-${config.releaseVersion}-${deployEnv}"
    def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
    def gate = gateutil.gate(null, false, true, false, "${baseDisplayTag}", 'Approve Release?')
    def crNumber = gate.crNumber
    if (crNumber != null) {
      qaApproval(crNumber, baseDisplayTag, config, repo)
    }
  }
}

def qaApproval(crNumber, baseDisplayTag, config, repo) {
  def helmUtil = new com.aristotlecap.pipeline.util.HelmUtil()
  def commons = new com.aristotlecap.pipeline.Commons()

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: env.TOOL_AGENT, args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]){
    node(POD_LABEL) {
      currentBuild.displayName = "${baseDisplayTag}-${crNumber}"

      //move the image to release repo
      def dockerfileToTagMap = config.dockerfiles
      dockerfileToTagMap.each{ key, value ->
        def imageTag = "${config.branch_name}-${value}-${config.build_number}"

        def image = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${repo.organizationName}/${repo.appGitRepoName}:${imageTag}"
        def approveImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${repo.organizationName}/${repo.appGitRepoName}:${imageTag}-${crNumber}"
        println  approveImage
        def releaseApproveImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${repo.organizationName}/${repo.appGitRepoName}:${imageTag}-${crNumber}"
        println releaseApproveImage
        def releaseImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${repo.organizationName}/${repo.appGitRepoName}:${imageTag}"
        println releaseImage

        helmUtil.pushImageToProdDtr(image, approveImage)
        helmUtil.pushImageToProdDtr(image, releaseApproveImage)
        helmUtil.pushImageToProdDtr(image, releaseImage)
      }
    }
  }

  def dockerfilesString = helmUtil.getStringFromMap(config.dockerfiles, ":")
  def strArray = []
  config.charts.each { it ->
    def str = helmUtil.getStringFromMap(it, ":")
    strArray.push(str)
  }
  def chartsString = strArray.join("\n")
  println 'chartsString->' + chartsString

  def secretsStr = commons.getStringFromMap(config.secrets)
  def valuesStr = commons.getStringFromMap(config.values)

  def helmReposStr = helmUtil.getStringFromMap(config.helmRepos, "=")
  stage('trigger downstream job') {
    build job: "${env.opsReleaseJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'projectType', value: "helm"],
      [$class: 'StringParameterValue', name: 'buildNumber', value: config.build_number],
      [$class: 'StringParameterValue', name: 'crNumber', value: crNumber],
      [$class: 'StringParameterValue', name: 'gitBranchName', value: config.branch_name],
      [$class: 'StringParameterValue', name: 'gitCommit', value: repo.gitCommit],
      [$class: 'StringParameterValue', name: 'gitScm', value: repo.gitScm],
      [$class: 'StringParameterValue', name: 'releaseVersion', value: config.releaseVersion],
      [$class: 'StringParameterValue', name: 'prodEnv', value: config.prodEnv],
      [$class: 'StringParameterValue', name: 'builderTag', value: config.builderTag],
      [$class: 'StringParameterValue', name: 'secrets', value: secretsStr],
      [$class: 'StringParameterValue', name: 'values', value: valuesStr],
      [$class: 'StringParameterValue', name: 'helmRepos', value: helmReposStr],
      [$class: 'StringParameterValue', name: 'charts', value: chartsString],
      [$class: 'StringParameterValue', name: 'dockerfiles', value: dockerfilesString],
      [$class: 'StringParameterValue', name: 'organizationName', value: repo.organizationName],
      [$class: 'StringParameterValue', name: 'appGitRepoName', value: repo.appGitRepoName],
      [$class: 'StringParameterValue', name: 'namespace', value: String.valueOf(config.namespace)]
    ]
  }
}
