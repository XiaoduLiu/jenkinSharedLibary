package com.aristotlecap.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, downstreamProjects) {

  def gitCommit
  def pomversion

  def projectType = "${projectTypeParam}"
  def imageTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = imageTag

  def organizationName
  def appGitRepoName
  def appDtrRepo
  def gitScm

  def commons = new com.aristotlecap.pipeline.Commons()

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
  ]) {
    node(POD_LABEL) {
      try {
        stage ('Clone') {
          // Clean workspace before doing anything
          deleteDir()
          checkout scm

          gitCommit=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
          echo gitCommit

          String gitRemoteURL = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
          echo gitRemoteURL

          gitScm = "git@github.westernasset.com:" + gitRemoteURL.drop(32)
          echo gitScm

          String shortName = gitRemoteURL.drop(32).reverse().drop(4).reverse()
          echo shortName

          def names = shortName.split('/')

          echo names[0]
          echo names[1]

          organizationName = names[0]
          appGitRepoName = names[1]
        }

        imageTag = commons.setJobLabelJavaProject(gitBranchName, buildNumber)
        commons.mavenSnapshotBuild()

        build job: "${env.siteDeployJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
          [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
          [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
          [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)]
        ]

        if (downstreamProjects != null) {
          int dem = downstreamProjects.size()
          int i = 0
          while (i < dem) {
            def jobName = downstreamProjects[i]
            build job: "${jobName}", wait: false
            echo jobName
            i=i+1
          }
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  buildReleaseCheck(gitScm, gitBranchName, gitCommit, buildNumber, builderTag, imageTag)
}

def buildReleaseCheck(gitScm, gitBranchName, gitCommit, buildNumber, builderTag, imageTag) {
  stage("Ready to Release?") {
    checkpoint "Ready Do Release"
    currentBuild.displayName = imageTag

    def userInput
    def didTimeout
    def didAbort
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
      try {
        buildRelease(gitScm, gitBranchName, gitCommit, buildNumber, builderTag, imageTag)
      } catch (err) {
        print err
        throw err
      }
    }
  }
}

def buildRelease(gitScm, gitBranchName, gitCommit, buildNumber, builderTag, imageTag) {
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
  ]) {
    node(POD_LABEL) {
      def build = new com.aristotlecap.pipeline.devRelease.releaseBuild_mavenLib()
      build.build(gitScm, gitBranchName, gitCommit, buildNumber, builderTag, imageTag)
    }
  }
}
