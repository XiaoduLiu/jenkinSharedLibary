package com.westernasset.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          qaEnvs, prodEnv, drEnv, releaseVersion, templates,
          secrets, buildSteps, mixCaseRepo, dockerfileToTagMapString) {

  def pomversion

  def projectType = "${projectTypeParam}"
  def imageTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = imageTag

  def repo

  def commons = new com.westernasset.pipeline.Commons()
  def repoUtil = new com.westernasset.pipeline.util.RepoUtil()

  def builderImage = "${env.TOOL_BUSYBOX}"
  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  }
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'sonar', image: "${env.TOOL_SONAR_SCANNER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
    ]) {
      node(POD_LABEL) {

      try {
        repo = commons.clone()
        imageTag = commons.setJobLabelNonJavaProject(gitBranchName, repo.gitCommit, buildNumber, releaseVersion)

        commons.localBuildSteps("Build & Test", "${buildSteps}")
        commons.sonarProcess( "${gitBranchName}")

        appDtrRepo = repoUtil.clean(repo.appDtrRepo, (mixCaseRepo == 'yes' || mixCaseRepo == 'true'))

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        def dockerfileToTagMap = commons.getMapFromString(dockerfileToTagMapString)
        dockerfileToTagMap.each{ dockerFile, tag ->
          def dockerfilefullpath = "${workspace}/${dockerFile}"
          commons.hadolintDockerFile("${dockerFile}")
          commons.dockerBuildForMultiImages(env.IMAGE_REPO_URI, env.IMAGE_REPO_NONPROD_KEY, repo.appDtrRepo, tag, repo.organizationName, repo.appGitRepoName, gitBranchName, repo.gitCommit, dockerfilefullpath, buildNumber, 'Docker Build')
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonProdEnvs, true, false, false, currentBuild.displayName, 'Deploy To Non-Prod?', 'Approve Non-Prod Deploy?', 'Ready for Release')
  if (gate.deployEnv != null) {
    nonProdDeploy(projectTypeParam, repo.gitScm, gitBranchName, repo.gitCommit, buildNumber,
                  gate.deployEnv, repo.organizationName, repo.appGitRepoName, builderTag,
                  imageTag, templates, secrets,
                  gate.releaseFlag, nonProdEnvs, qaEnvs, prodEnv, drEnv,
                  releaseVersion, repo.appDtrRepo, dockerfileToTagMapString)
  }
}

def nonProdDeploy(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                       deployEnvironment, organizationName, appGitRepoName, builderTag,
                       imageTag, templates, secrets,
                       releaseFlag, nonProdEnvs, qaEnvs, prodEnv, drEnv,
                       releaseVersion, appDtrRepo, dockerfileToTagMapString) {

  def builderImage = "${env.TOOL_BUSYBOX}"
  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  }

  def tag;
  def nonProdDeployDisplayTag;
  def qaPassFlag
  def baseDisplayTag

  def commons = new com.westernasset.pipeline.Commons()
  def envCluster = commons.getNonProdEnvDetailsForService(deployEnvironment)
  def deployEnv = envCluster.deployEnv
  def clusterName = (envCluster.clusterName != null)? envCluster.clusterName: 'pas-development'

  podTemplate(
    cloud: "${clusterName}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {

      currentBuild.displayName = imageTag + '-' + deployEnv
      echo currentBuild.displayName
      baseDisplayTag = currentBuild.displayName

      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
      commons.nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                                 organizationName, appGitRepoName, "null", "${env.IMAGE_REPO_URI}", "null",
                                 imageTag, templates, secrets, dockerfileToTagMapString, null)
    }
  }
  if (qaPassFlag) {
    def qaApprove = new com.westernasset.pipeline.qa.qaApprove()
    qaApprove.approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
                      imageTag, organizationName, appGitRepoName, prodEnv, drEnv,
                      'null', 'null', projectTypeParam, templates, secrets,
                      releaseVersion, dockerfileToTagMapString, baseDisplayTag, 'null')

    build job: "${env.nonProdReleaseDeployJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
      [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(imageTag)],
      [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
      [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
      [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
      [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
      [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
      [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
      [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
      [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
      [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
      [$class: 'StringParameterValue', name: 'nonProdEnvs', value: String.valueOf(nonProdEnvs)],
      [$class: 'StringParameterValue', name: 'releaseImageTag', value: String.valueOf(imageTag)]
    ]
  }
}
