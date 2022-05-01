package com.aristotlecap.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          qaEnvs, appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString) {

  def gitCommit
  def pomversion

  def projectType = "${projectTypeParam}"
  def displayTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = displayTag

  def organizationName
  def appGitRepoName
  def appDtrRepo
  def gitScm

  def commons = new com.aristotlecap.pipeline.Commons()
  
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  properties([
    copyArtifactPermission('*'),
  ]);
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'aws', image: "${env.TOOL_AWS}", ttyEnabled: true, command: 'cat')
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

          displayTag = commons.setJobLabelJavaProject(gitBranchName, buildNumber)
          commons.mavenSnapshotBuild()
          commons.downloadArtifact(appArtifactsString)
          commons.archive(appArtifactsString)

          echo nonProdEnvs
          def nonProdEnvList = nonProdEnvs.split("\n")
          def dem = nonProdEnvList.length
          def i = 0
          while (i < dem) {
            echo "i=" + i
            def deployEnv = nonProdEnvList[i]
            echo deployEnv

            println 'inside the deployment logic'
            println deployEnv

            def keyMap = commons.getMapFromString(S3KeyMapString)

            def key = keyMap.get(deployEnv)
            println 's3 key for ' + deployEnv + ' is ' + key

            def projectName = currentBuild.projectName
            println projectName + ":" + buildNumber
            commons.pushArtifactsToDatabrickS3Bucket("${env.DATABRICK_CREDENTIAL}", appArtifactsString, nonprodBucket, key, projectName, buildNumber, false)

            i = i+1
          }

        } catch (err) {
          currentBuild.result = 'FAILED'
          throw err
        }
      }
  }
  nonprodDeploy(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
               organizationName, appGitRepoName, nonProdEnvs, qaEnvs, displayTag,
               appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString, builderTag)

}

def nonprodDeploy(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
                  organizationName, appGitRepoName, nonProdEnvs, qaEnvs, displayTag,
                  appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString, builderTag) {

  stage("Should I deploy to Non-Prod?") {
    checkpoint "Deploy To Non-Prod"
    def didTimeout = false
    def userInput
    def deployEnv
    def releaseFlag
    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(
          id: 'Proceed1', message: 'Approve Non-Prod Deploy?', parameters: [
            [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Ready for Maven Release?', name: 'releaseFlag'],
            [$class: 'ChoiceParameterDefinition', choices: nonProdEnvs, description: 'Environments', name: 'env']
        ])
      }
      deployEnv = userInput['env']
      releaseFlag = userInput['releaseFlag']
    } catch(err) { // timeout reached or input false
      print err
      didTimeout = true
    }
    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      currentBuild.displayName = displayTag + '-' + deployEnv
      echo currentBuild.displayName
      nonProdDeployLogic(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
                         organizationName, appGitRepoName, nonProdEnvs, qaEnvs, displayTag,
                         appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString, deployEnv,
                         releaseFlag, builderTag)
    }
  }

}

def nonProdDeployLogic(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
                       organizationName, appGitRepoName, nonProdEnvs, qaEnvs, displayTag,
                       appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString, deployEnv,
                       releaseFlag, builderTag) {

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  def commons = new com.aristotlecap.pipeline.Commons()

  def build = new com.aristotlecap.pipeline.devRelease.releaseBuild_mavenLib()

  def released = false
  def releasedVersion
  properties([
    copyArtifactPermission('*'),
  ]);
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'aws', image: "${env.TOOL_AWS}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
  ]) {
    node(POD_LABEL) {
      println 'inside the deployment logic'
      currentBuild.displayName = displayTag + '-' + deployEnv
      nonProdDeployDisplayTag = currentBuild.displayName

      echo currentBuild.displayName
      echo buildNumber
      echo currentBuild.projectName

      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      println deployEnv

      def keyMap = commons.getMapFromString(S3KeyMapString)

      def key = keyMap.get(deployEnv)
      println 's3 key for ' + deployEnv + ' is ' + key

      stage('Deploy to Non-Prod') {
        def projectName = currentBuild.projectName
        println projectName + ":" + buildNumber
        commons.downloadArtifact(appArtifactsString)
        commons.pushArtifactsToDatabrickS3Bucket("${env.DATABRICK_CREDENTIAL}", appArtifactsString, nonprodBucket, key, projectName, buildNumber, false)
      }
      def qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
      echo "qaPassFlag::::"
      if (qaPassFlag) {
        echo "qaPassFlag is true!!!"
      } else {
        echo "qaPassFlag is false!!!"
      }

      if (releaseFlag) {
        releasedVersion = build.build(gitScm, gitBranchName, gitCommit, buildNumber, builderTag, 'null')
        released = true
        sh """
          ls -la target/*
        """
        commons.archive(appArtifactsString)
      }
    }
  }

  if (released) {
    qaApproveLogic(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
                   organizationName, appGitRepoName, nonProdEnvs, qaEnvs, displayTag,
                   appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString, builderTag,
                   deployEnv, releasedVersion)
  }
}

def qaApproveLogic(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
                  organizationName, appGitRepoName, nonProdEnvs, qaEnvs, displayTag,
                  appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString, builderTag,
                  deployEnv, releasedVersion) {

  stage("QA Approve?") {
    checkpoint "QA Approve"

    currentBuild.displayName = displayTag + '-' + deployEnv

    def didTimeout = false
    def userInput

    try {
      timeout(time: 60, unit: 'SECONDS') {
        userInput = input(
          id: 'userInput', message: 'Approve Release?', parameters: [
          [$class: 'TextParameterDefinition', defaultValue: '', description: 'CR Number', name: 'crNumber']
        ])
        echo ("CR Number: "+userInput)
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }

    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      stage("QA approve") {
        echo "Approle release for CR ${userInput}"
        echo "Trigger the production release task!!!"

        currentBuild.displayName = displayTag + '-' + deployEnv + '-' + userInput

        stage('trigger downstream job') {
          echo buildNumber
          echo gitBranchName
          echo gitCommit
          echo gitScm
          echo organizationName
          echo appGitRepoName
          echo prodBucket

          build job: "${env.opsReleaseJob}", wait: false, parameters: [
            [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
            [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
            [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(userInput)],
            [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
            [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
            [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
            [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
            [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
            [$class: 'StringParameterValue', name: 'appArtifacts', value: String.valueOf(appArtifactsString)],
            [$class: 'StringParameterValue', name: 'prodBucket', value: String.valueOf(prodBucket)],
            [$class: 'StringParameterValue', name: 'S3KeyMap', value: String.valueOf(S3KeyMapString)],
            [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releasedVersion)],
            [$class: 'StringParameterValue', name: 'upstreamJobName', value: String.valueOf(env.JOB_NAME)],
            [$class: 'StringParameterValue', name: 'upstreamBuildNumber', value: String.valueOf(env.BUILD_NUMBER)]
          ]
        }
      }
    }
  }
}
