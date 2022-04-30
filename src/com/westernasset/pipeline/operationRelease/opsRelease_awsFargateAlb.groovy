package com.westernasset.pipeline.operationRelease;

def build(buildNumber, crNumber, gitBranchName, gitCommit, gitScm,
          organizationName, appGitRepoName, releaseVersion, budgetCode) {

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
      deploy(buildNumber, crNumber, gitBranchName, gitCommit, gitScm,
             organizationName, appGitRepoName, releaseVersion, budgetCode)
    }
  }
}

def deploy(buildNumber, crNumber, gitBranchName, gitCommit, gitScm,
           organizationName, appGitRepoName, releaseVersion, budgetCode) {

  def commons = new com.westernasset.pipeline.Commons()
  def fargate = new com.westernasset.pipeline.util.FargateUtil()

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'cdk', image: "${env.TOOL_CDK}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws'),
        persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh'),
        persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
    ]) {
    node(POD_LABEL) {
      println 'inside the deployment logic'
      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
      echo currentBuild.displayName

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}"
      sh """
        git config --global user.email "jenkins@westernasset.com"
        git config --global user.name "Jenkins Agent"
        git config --global http.sslVerify false
        git config --global push.default matching
        git config -l

        ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}"'
        ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
      """

      stage('Deploy to Prod') {
        commons.setNpmrcFilelink()
        imageTag = "${env.IMAGE_REPO_URI}\\/${env.IMAGE_REPO_NONPROD_KEY}\\/${organizationName}\\/${appGitRepoName}:${gitBranchName}-${releaseVersion}-${buildNumber}"
        fargate.awsDeployment('prod', 'awsFargateAlb', organizationName, appGitRepoName, budgetCode, imageTag, true)
      }
    }
  }
}
