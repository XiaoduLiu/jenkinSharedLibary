package com.aristotlecap.pipeline.operationRelease;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
          organizationName, appGitRepoName, gitScm, gitCommit, crNumber,
          verifyFromChange) {

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
      deploy(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
             organizationName, appGitRepoName, gitScm, gitCommit, crNumber,
             verifyFromChange)
    }
  }
}

def deploy(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
           organizationName, appGitRepoName, gitScm, gitCommit, crNumber,
           verifyFromChange) {

  def commons = new com.aristotlecap.pipeline.Commons()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sqitch', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh')
  ])  {
    node(POD_LABEL) {
      try {
        currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

        echo currentBuild.displayName

        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def gitReleaseTagName = "${gitBranchName}-${releaseVersion}-${crNumber}"
        sh """
          git config --global user.email "jenkins@westernasset.com"
          git config --global user.name "Jenkins Agent"
          git config --global http.sslVerify false
          git config --global push.default matching
          git config -l

          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}"'
          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
        """

        //echo sh(script: 'env|sort', returnStdout: true)
        commons.snowflackScriptTokenReplacement('prod');
        def hasTarget = commons.checkSqichTarget('prod')
        def changeFrom = commons.snowflakeDeployStatus(organizationName, appGitRepoName, 'prod', true, verifyFromChange, hasTarget)
        stage('Deploy to Prod') {
          commons.snowflakeDeploy('prod', true, 'deploy', hasTarget)
        }
        stage('Verify') {
          commons.snowflakeDeploy('prod', true, 'verify', changeFrom, hasTarget)
        }

      } catch(err) {
        println err
      }
    }
  }
  rollback(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
               organizationName, appGitRepoName, gitScm, gitCommit, crNumber, verifyFromChange)
}

def rollback(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
             organizationName, appGitRepoName, gitScm, gitCommit, crNumber, verifyFromChange) {

  def didAbort = false
  def didTimeout = false

  stage("Revert the Change?") {
    checkpoint "Revert the Change"
    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Revert?')
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
    rollbackLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
                  organizationName, appGitRepoName, gitScm, gitCommit, crNumber, verifyFromChange)
  }
}

def rollbackLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
                  organizationName, appGitRepoName, gitScm, gitCommit, crNumber, verifyFromChange) {

  def commons = new com.aristotlecap.pipeline.Commons()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sqitch', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
  ])  {
    node(POD_LABEL) {
      try {
        println 'inside the deployment logic'

        currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}-Revert"

        echo currentBuild.displayName

        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        //echo sh(script: 'env|sort', returnStdout: true)
        commons.snowflackScriptTokenReplacement('prod');
        def hasTarget = commons.checkSqichTarget('prod')
        commons.snowflakeDeployStatus(organizationName, appGitRepoName, 'prod', true, verifyFromChange, hasTarget)
        stage('Revert the Change') {
          commons.snowflakeDeploy('prod', true, 'revert', hasTarget)
        }
      } catch(err) {
        println err
      }
    }
  }
}
