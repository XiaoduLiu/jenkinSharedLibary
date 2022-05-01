package com.aristotlecap.pipeline;

import com.aristotlecap.pipeline.steps.Hadolint

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          liquibaseChangeLog, liquibaseBuilderTag, qaEnvs, prodEnv, drEnv,
          releaseVersion, templates, secrets) {

  def repo
  def gitCommit
  def pomversion

  def projectType = "${projectTypeParam}"
  def imageTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = imageTag

  def organizationName
  def appGitRepoName
  def appDtrRepo
  def gitScm

  def hadolint = new Hadolint()

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'busy', image: "${env.TOOL_BUSYBOX}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      hadolint.containerTemplate()
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-sencha-cache', mountPath: '/home/jenkins/senchaBuildCache'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {
      def commons = new com.aristotlecap.pipeline.Commons()
      repo = commons.clone()

      imageTag = commons.setJobLabelJavaProject(gitBranchName, buildNumber)
      commons.mavenSnapshotBuild()
      hadolint.lint()
      commons.dockerBuild(env.IMAGE_REPO_URI, env.IMAGE_REPO_NONPROD_KEY, repo.appDtrRepo, imageTag, repo.organizationName,
                          repo.appGitRepoName, gitBranchName, repo.gitCommit, 'null', 'Docker Build')

      build job: "${env.siteDeployJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
        [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(repo.gitCommit)],
        [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
        [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(repo.gitScm)]
      ]
    }
  }
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonProdEnvs, true, false, false, currentBuild.displayName)
  if (gate.deployEnv != null) {

    nonProdDeploy(projectTypeParam, repo.gitScm, gitBranchName, repo.gitCommit, buildNumber,
                  gate.deployEnv, repo.organizationName, repo.appGitRepoName, liquibaseChangeLog, builderTag,
                  env.liquibaseProjectFolder, liquibaseBuilderTag, imageTag, templates, secrets,
                  gate.releaseFlag, nonProdEnvs, qaEnvs, prodEnv, drEnv,
                  releaseVersion, repo.appDtrRepo)
  }
}

def nonProdDeploy(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                  deployEnvironment, organizationName, appGitRepoName, liquibaseChangeLog, builderTag,
                  liquibaseProjectFolder, liquibaseBuilderTag, imageTag, templates,secrets,
                  releaseFlag, nonProdEnvs, qaEnvs, prodEnv, drEnv,
                  releaseVersion, appDtrRepo) {

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  def liquibaseImage = "${env.TOOL_BUSYBOX}"
  if (liquibaseBuilderTag != null && liquibaseBuilderTag != 'null') {
    liquibaseImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${liquibaseBuilderTag}"
  }

  def released = false;

  def tag;
  def nonProdDeployDisplayTag;

  def build = new com.aristotlecap.pipeline.devRelease.releaseBuild_mavenDocker()

  def commons = new com.aristotlecap.pipeline.Commons()
  def envCluster = commons.getNonProdEnvDetailsForService(deployEnvironment)
  def deployEnv = envCluster.deployEnv
  def clusterName = (envCluster.clusterName != null)? envCluster.clusterName: 'pas-development'
  def qaPassFlag

  podTemplate(
    cloud: "${clusterName}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'liquibase', image: "${liquibaseImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {
      currentBuild.displayName = imageTag + '-' + deployEnv
      nonProdDeployDisplayTag = currentBuild.displayName

      echo currentBuild.displayName
      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
      commons.nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                                 organizationName, appGitRepoName, liquibaseChangeLog, "${env.IMAGE_REPO_URI}", liquibaseProjectFolder,
                                 imageTag, templates, secrets, null, null)
    }
  }

  if (qaPassFlag) {
    podTemplate(
      cloud: "pas-development",
      serviceAccount: 'jenkins',
      namespace: 'devops-jenkins',
      containers: [
        containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
        containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'liquibase', image: "${liquibaseImage}", ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
        containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
        containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
        persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
    ]) {
      node(POD_LABEL) {
        stage('Build Release') {
          //echo buildNumber
          tagAndDisplay = build.build(gitBranchName, buildNumber, builderTag,
                                 gitScm, gitCommit, projectTypeParam, appDtrRepo,
                                 organizationName, appGitRepoName, prodEnv, drEnv, liquibaseChangeLog,
                                 liquibaseBuilderTag, releaseVersion, imageTag, templates,
                                 secrets, nonProdEnvs, 'null', 'null')
          def arr = tagAndDisplay.tokenize('::')
          tag = arr[0]
          nonProdDeployDisplayTag = arr[1]
          released = true
        }
      }
    }
  }

  if (released) {
    def qaApprove = new com.aristotlecap.pipeline.qa.qaApprove()
    qaApprove.approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
                      tag, organizationName, appGitRepoName, prodEnv, drEnv,
                      liquibaseChangeLog, liquibaseBuilderTag, projectTypeParam, templates, secrets,
                      'null', 'null', nonProdDeployDisplayTag, 'null')

    if (projectTypeParam == 'javaApp') {
      build job: "${env.nonProdReleaseDeployJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
        [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(tag)],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
        [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
        [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
        [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
        [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
        [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
        [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: String.valueOf(liquibaseChangeLog)],
        [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: String.valueOf(liquibaseBuilderTag)],
        [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
        [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
        [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
        [$class: 'StringParameterValue', name: 'nonProdEnvs', value: String.valueOf(nonProdEnvs)],
        [$class: 'StringParameterValue', name: 'releaseImageTag', value: String.valueOf(imageTag)]
      ]
    }
  }
}
