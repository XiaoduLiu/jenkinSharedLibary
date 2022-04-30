package com.westernasset.pipeline.operationRelease;

def build(gitBranchName, gitScm, gitCommit, buildNumber, organizationName,
          appGitRepoName, appArtifacts, prodBucket, S3KeyMap, releaseVersion,
          upstreamJobName, upstreamBuildNumber, crNumber) {

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
    def userInput

    currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Release?')
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }
    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      mavenS3DeployImpl(gitBranchName, gitScm, gitCommit, buildNumber, organizationName,
                        appGitRepoName, appArtifacts, prodBucket, S3KeyMap, releaseVersion,
                        upstreamJobName, upstreamBuildNumber, crNumber)
    }
  }
}

def mavenS3DeployImpl(gitBranchName, gitScm, gitCommit, buildNumber, organizationName,
                      appGitRepoName, appArtifacts, prodBucket, S3KeyMap, releaseVersion,
                      upstreamJobName, upstreamBuildNumber, crNumber) {

  def commons = new com.westernasset.pipeline.Commons()
  properties([
    copyArtifactPermission('*'),
  ]);
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'aws', image: "${env.TOOL_AWS}", ttyEnabled: true, command: 'cat')
  ]) {
    node(POD_LABEL) {
      println 'inside the deployment logic'

      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

      echo currentBuild.displayName

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      def keyMap = commons.getMapFromString(S3KeyMap)

      def key = keyMap.get('prod')
      println 's3 key for prod is ' + key

      stage('Deploy to Prod') {
        def myArchiveList = commons.getArchiveList(appArtifacts)
        commons.copyArtifactsFromUpstream(upstreamJobName, upstreamBuildNumber, myArchiveList)
        sh """
          ls -la
        """
        commons.downloadArtifact(appArtifacts)
        commons.pushArtifactsToDatabrickS3Bucket("${env.DATABRICK_CREDENTIAL}", appArtifacts, prodBucket, key, 'null', buildNumber, true)
      }
    }
  }
}
