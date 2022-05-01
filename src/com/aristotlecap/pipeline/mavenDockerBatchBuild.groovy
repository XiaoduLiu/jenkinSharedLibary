package com.aristotlecap.pipeline;

import com.aristotlecap.pipeline.steps.Hadolint

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvironments,
          qaEnvs, prodEnv, drEnv, releaseVersion, templates,
          secrets) {

  def gitCommit
  def pomversion

  def projectType = "${projectTypeParam}"
  def imageTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = imageTag

  def organizationName
  def appGitRepoName
  def appDtrRepo
  def gitScm
  def repo

  def commons = new com.aristotlecap.pipeline.Commons()

  def hadolint = new Hadolint()

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
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
      try {
        stage ('Clone') {
          repo = commons.clone(false)
          appDtrRepo = repo.organizationName + '/' + repo.appGitRepoName
          echo "appDtrRepo -> ${appDtrRepo}"
        }

        imageTag = commons.setJobLabelJavaProject(gitBranchName, buildNumber)
        imageTag = imageTag.toLowerCase()
        hadolint.lint()
        commons.mavenSnapshotBuild()
        commons.dockerBuild("${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_NONPROD_KEY}", "${appDtrRepo}", "${imageTag}", organizationName, appGitRepoName, gitBranchName, gitCommit, 'null', 'Docker Build')

        build job: "${env.siteDeployJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
          [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(repo.gitCommit)],
          [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
          [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(repo.gitScm)]
        ]

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }

  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonProdEnvironments, false, false, false, currentBuild.displayName, "Should I deploy to Non-Prod?", "Approve Non-Prod Deploy?", "Ready for Release")
  if (gate.deployEnv != null) {
    print "Deploy to nonprod ENV"
    print nonProdEnvironments
    def (deployEnv, clusterName) = commons.getNonProdCluster(nonProdEnvironments, gate.deployEnv)
    println(deployEnv)
    println(clusterName)

    commons.nonprodDeployment(deployEnv, clusterName, repo, gitBranchName, templates, secrets, imageTag, false, false)

    batchRelease(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                 nonProdEnvironments, qaEnvs, prodEnv, drEnv, releaseVersion,
                 organizationName, appGitRepoName, appDtrRepo, gitCommit, templates,
                 secrets, imageTag)

  }

}

def batchRelease(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                 nonProdEnvs, qaEnvs, prodEnv, drEnv, releaseVersion,
                 organizationName, appGitRepoName, appDtrRepo, gitCommit, templates,
                 secrets, imageTag) {

  def userInput
  stage("Ready for Release?") {
    checkpoint "Ready for Release"

    def didAbort = false
    def didTimeout = false

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Release?')
      }
    } catch(err) { // timeout reached or input false
      def user = err.getCauses()[0].getUser()
      if('SYSTEM' == user.toString()) { // SYSTEM means timeout.
        didTimeout = true
      } else {
        didAbort = true
        echo "Aborted by: [${user}]"
      }
    }

    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else if (didAbort) {
      // do something else
      echo "this was not successful"
      currentBuild.result = 'SUCCESS'
    } else {
      mavenDockerBatchBuildReleaseLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                                            nonProdEnvs, qaEnvs, prodEnv, drEnv, releaseVersion,
                                            organizationName, appGitRepoName, appDtrRepo, gitCommit, templates,
                                            secrets, imageTag)
    }
  }
}

def mavenDockerBatchBuildReleaseLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                                      nonProdEnvs, qaEnvs, prodEnv, drEnv, releaseVersion,
                                      organizationName, appGitRepoName, appDtrRepo, gitCommit, templates,
                                      secrets, imageTag) {

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  def released = false;

  def tag;
  def nonProdDeployDisplayTag;

  def build = new com.aristotlecap.pipeline.devRelease.releaseBuild_mavenDocker()

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {
      stage('Build Release') {
        tagAndDisplay = build.build(gitBranchName, buildNumber, builderTag,
                                    gitScm, gitCommit, projectTypeParam, appDtrRepo,
                                    organizationName, appGitRepoName, prodEnv, drEnv,
                                    'null','null',releaseVersion, imageTag, templates,
                                    secrets, nonProdEnvs, imageTag, 'null')

        def arr = tagAndDisplay.tokenize('::')
        tag = arr[0]
        nonProdDeployDisplayTag = arr[1]
        released = true
      }
    }
  }
  if (released) {
    def qaApprove = new com.aristotlecap.pipeline.qa.qaApprove()
    qaApprove.approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
                      tag, organizationName, appGitRepoName, prodEnv, drEnv,
                      'null', 'null', projectTypeParam, templates, secrets,
                      'null', 'null', nonProdDeployDisplayTag, 'null')

    if (projectTypeParam == 'javaApp') {
      build job: "${env.nonProdReleaseDeployJob}", wait: false, parameters: [
            [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
            [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(imageTag)],
            [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
            [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
            [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
            [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
            [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
            [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
            [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: 'null'],
            [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: 'null'],
            [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
            [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
            [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
            [$class: 'StringParameterValue', name: 'nonProdEnvs', value: String.valueOf(nonProdEnvs)],
            [$class: 'StringParameterValue', name: 'releaseImageTag', value: String.valueOf(imageTag)]
      ]
    }
  }
}
