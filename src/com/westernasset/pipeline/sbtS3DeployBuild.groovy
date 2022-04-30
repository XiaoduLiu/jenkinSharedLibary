package com.westernasset.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          qaEnvs, appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString) {

  def gitCommit

  def projectType = "${projectTypeParam}"
  def displayTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = displayTag

  def organizationName
  def appGitRepoName
  def gitScm
  def versionString

  def commons = new com.westernasset.pipeline.Commons()

  def sbtBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sbt', image: "${sbtBuilderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'aws', image: "${awsImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-scala-sbt-cache', mountPath: '/home/jenkins/.sbt'),
      persistentVolumeClaim(claimName: 'jenkins-scala-ivy-cache', mountPath: '/home/jenkins/.ivy2')
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

        stage ('Snapshot Build') {
          commons.sbtSnapshotBuild();
          def files = findFiles(glob: '**/*.jar')
          files.eachWithIndex { item, index ->
            println item
            println index
            def filePath = item.toString()
            if (!filePath.contains('source') && !filePath.contains('javadoc')) {
              print 'uploading -> ' + filePath
              commons.archive(filePath)
            }
          }
        }

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        String tempString = sh(returnStdout: true, script: "grep version $workspace/version.sbt").trim()
        tempString = tempString.split(":=")[1]
        versionString = tempString.substring(2, tempString.length()-1)

        displayTag = commons.setJobLabelNonJavaProject(gitBranchName, gitCommit, buildNumber, versionString)

        echo nonProdEnvs
        def nonProdEnvList = nonProdEnvs.split("\n")
        def dem = nonProdEnvList.length
        def i = 0
        while (i < dem) {
          echo "i=" + i
          def deployEnv = nonProdEnvList[i]
          echo deployEnv
          def keyMap = commons.getMapFromString(S3KeyMapString)

          def key = keyMap.get(deployEnv)
          println 's3 key for ' + deployEnv + ' is ' + key

          println 'pushing the following to sws S3 ->' + appArtifactsString
          def projectName = currentBuild.projectName
          println projectName + ":" + buildNumber
          commons.pushArtifactsToDatabrickS3Bucket("${env.DATABRICK_CREDENTIAL}", appArtifactsString, nonprodBucket, key, projectName, buildNumber)
        }

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }

  nonprodDeploy(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
                builderTag, organizationName, appGitRepoName, nonProdEnvs, qaEnvs,
                displayTag, appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString)
}

def nonprodDeploy(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
                  builderTag, organizationName, appGitRepoName, nonProdEnvs, qaEnvs,
                  displayTag, appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString) {

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
            [$class: 'ChoiceParameterDefinition', choices:"${nonProdEnvs}" , description: 'Environments', name: 'env']
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
                         builderTag, organizationName, appGitRepoName, nonProdEnvs, qaEnvs,
                         deployEnv, releaseFlag, currentBuild.displayName, appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString)
    }
  }
}

def nonProdDeployLogic(projectTypeParam, gitBranchName, gitScm, gitCommit, buildNumber,
                       builderTag, organizationName, appGitRepoName, nonProdEnvs, qaEnvs,
                       deployEnv, releaseFlag, baseDisplayTag, appArtifactsString, nonprodBucket, prodBucket, S3KeyMapString) {

  echo baseDisplayTag

  def commons = new com.westernasset.pipeline.Commons()

  def qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)

  def sbtBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  def awsImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:aws-1.16.96"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sbt', image: "${sbtBuilderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'aws', image: "${awsImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-scala-sbt-cache', mountPath: '/home/jenkins/.sbt'),
      persistentVolumeClaim(claimName: 'jenkins-scala-ivy-cache', mountPath: '/home/jenkins/.ivy2')
  ]) {
    node(POD_LABEL) {
      stage ('Deploy to nonprod') {

        deleteDir()
        checkout scm
        sh "git reset --hard ${gitCommit}"

        println deployEnv

        def keyMap = commons.getMapFromString(S3KeyMapString)

        def key = keyMap.get(deployEnv)
        println 's3 key for ' + deployEnv + ' is ' + key

        println 'pushing the following to sws S3 ->' + appArtifactsString

        stage('Deploy to Non-Prod') {
          def projectName = currentBuild.projectName
          println projectName + ":" + buildNumber
          commons.pushArtifactsToDatabrickS3Bucket("${env.DATABRICK_CREDENTIAL}", appArtifactsString, nonprodBucket, key, projectName, buildNumber)
        }
      }

      echo "qaPassFlag::::"
      if (qaPassFlag) {
        echo "qaPassFlag is true!!!"
      } else {
        echo "qaPassFlag is false!!!"
      }
    }
  }
  if (qaPassFlag) {
    stage('Build Release') {
      //echo buildNumber
      def build = new com.westernasset.pipeline.devRelease.releaseBuild_sbtDatabricks()
      build.sbtDatabricksLogic(gitBranchName, gitScm, gitCommit, buildNumber, builderDtrUri, builderRepo,
                               builderTag, organizationName, appGitRepoName, nonProdEnvs, qaEnvs,
                               nonprodBucket, prodBucket, S3KeyMapString, deployEnv, baseDisplayTag)
    }
  }
}
