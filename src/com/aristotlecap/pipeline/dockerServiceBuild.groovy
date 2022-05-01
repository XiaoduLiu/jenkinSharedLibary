package com.aristotlecap.pipeline;

import com.aristotlecap.pipeline.steps.Hadolint

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          qaEnvs, prodEnv, drEnv, releaseVersion, templates,
          secrets, buildSteps, mixCaseRepo, postDeploySteps, e2eEnv, e2eTestSteps) {

  def pomversion

  def imageTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = imageTag

  def commons = new com.aristotlecap.pipeline.Commons()
  def repo

  def hadolint = new Hadolint()

  def builderImage = (builderTag != 'null') ? "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}" : env.TOOL_BUSYBOX
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    nodeSelector: 'node-role.westernasset.com/builder=true',
    containers: [
      containerTemplate(name: 'jnlp', image: env.TOOL_AGENT, args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: builderImage, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: env.TOOL_DOCKER, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: env.TOOL_VAULT, ttyEnabled: true),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
      containerTemplate(name: 'sonar', image: env.TOOL_SONAR_SCANNER, ttyEnabled: true, command: 'cat'),
      hadolint.containerTemplate()
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
    ]) {
    node(POD_LABEL) {
      try {
        repo = commons.clone(mixCaseRepo)

        imageTag = commons.setJobLabelNonJavaProject(gitBranchName, repo.gitCommit, buildNumber, releaseVersion)

        commons.localBuildStepsForDockerServiceBuild("Build & Test", buildSteps)
        commons.sonarProcess(gitBranchName)

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()

        if (fileExists("${workspace}/Dockerfile")) {
          echo 'Yes, there is a Dockerfile exist at the project root and there is a docker build'
          hadolint.lint()
          commons.dockerBuild(env.IMAGE_REPO_URI, env.IMAGE_REPO_NONPROD_KEY, repo.appDtrRepo,
                              imageTag, repo.organizationName, repo.appGitRepoName,
                              gitBranchName, repo.gitCommit, 'null', 'Docker Build')
          if (e2eEnv != null) {
            commons.nonProdDeployLogicE2E(repo.gitScm, env.BRANCH_NAME, repo.gitCommit, env.BUILD_NUMBER, e2eEnv,
                                       repo.organizationName, repo.appGitRepoName, 'null', "${env.IMAGE_REPO_URI}", 'null',
                                       imageTag, templates, secrets, null, null)
            stage('E2E test') {
              sh(script: 'sleep 10', label: 'sleep')
              container("builder") {
                e2eTestSteps.each { script ->
                  println "script -> ${script}"
                  def testStatus = sh(script: "${script}", label: 'e2e test', returnStatus: true)
                  if (testStatus != 0) {
                    error("e2e test failed")
                  }
                }
              }
            }
          }
        } else {
          echo 'No, there is no DockerFile exist at the project root and there is no docker build'
        }

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonProdEnvs, true, false, false, currentBuild.displayName, "Should I deploy to Non-Prod?", "Approve Non-Prod Deploy?", "Ready for Release")
  if (gate.deployEnv != null) {
    nonProdDeploy(projectTypeParam, repo.gitScm, gitBranchName, repo.gitCommit, buildNumber,
                  gate.deployEnv, repo.organizationName, repo.appGitRepoName, builderTag, templates,
                  secrets, gate.releaseFlag, nonProdEnvs, qaEnvs, prodEnv,
                  drEnv, releaseVersion, repo.appDtrRepo, imageTag, postDeploySteps)
  }
}

def nonProdDeploy(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                  deployEnvironment, organizationName, appGitRepoName, builderTag, templates,
                  secrets, releaseFlag, nonProdEnvs, qaEnvs, prodEnv,
                  drEnv, releaseVersion, appDtrRepo, imageTag, postDeploySteps) {

  def builderImage = "${env.TOOL_BUSYBOX}"
  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  }

  def tag;
  def nonProdDeployDisplayTag;
  def qaPassFlag

  def commons = new com.aristotlecap.pipeline.Commons()
  def envCluster = commons.getNonProdEnvDetailsForService(deployEnvironment)
  def deployEnv = envCluster.deployEnv
  def clusterName = (envCluster.clusterName != null)? envCluster.clusterName: 'pas-development'

  podTemplate(
    cloud: "${clusterName}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    nodeSelector: 'node-role.westernasset.com/builder=true',
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

      echo currentBuild.displayName
      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      currentBuild.displayName = imageTag + '-' + deployEnv

      qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
      echo "qaPassFlag::::"
      if (qaPassFlag) {
        echo "true!!!"
      } else {
        echo "false!!!"
      }

      def deploymentName = commons.nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                                                      organizationName, appGitRepoName, 'null', "${env.IMAGE_REPO_URI}", 'null',
                                                      imageTag, templates, secrets, null, null)

      commons.postDeployStepsLogic(postDeploySteps, deploymentName)
    }
  }
  if (qaPassFlag) {
    def qaApprove = new com.aristotlecap.pipeline.qa.qaApprove()
    qaApprove.approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
                      imageTag, organizationName, appGitRepoName, prodEnv, drEnv,
                      "null", "null", projectTypeParam, templates, secrets,
                      releaseVersion, 'null', imageTag, postDeploySteps)
  }
}
