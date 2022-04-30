package com.westernasset.pipeline.operationRelease;

def build(gitBranchName, buildNumber, organizationName, appGitRepoName,
          gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
          accountAppfileMap, appfileStackMap, crNumber,
          templates, secrets) {
  def commons = new com.westernasset.pipeline.Commons()

  def cdkBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'busy', image: "${env.TOOL_BUSYBOX}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'cdk', image: "${cdkBuilderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ])  {
    node(POD_LABEL) {
      println 'inside the deployment logic'

      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

      echo currentBuild.displayName

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      def secretRoot = "secret/${organizationName}/${appGitRepoName}/prod"
      echo secretRoot

      def appRoleName = organizationName + '-' + appGitRepoName + '-prod'
      echo appRoleName

      def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, true);
      echo "application vault auth token -> ${appVaultAuthToken}"

      def secretRootBase = "secret/${organizationName}/${appGitRepoName}/prod"

      container('cdk') {
        commons.setNpmrcFilelink()
      }

      commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)

      commons.copySecretsToLocationForCDK(templates, secrets)

      stage('Synth && Diff for Prod') {
        container('cdk') {
          sh """
            pwd
          """
          commons.npmBuild()
          commons.awsCDKSynth(prodAccounts, accountAppfileMap, appfileStackMap)
          commons.awsCDKDiff(prodAccounts, accountAppfileMap, appfileStackMap)
        }
      }

    }
  }
  approveDeploy(gitBranchName, buildNumber, organizationName, appGitRepoName,
                gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
                accountAppfileMap, appfileStackMap, crNumber,
                templates, secrets)
}

def approveDeploy(gitBranchName, buildNumber, organizationName, appGitRepoName,
                  gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
                  accountAppfileMap, appfileStackMap, crNumber,
                  templates, secrets) {

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
      deploy(gitBranchName, buildNumber, organizationName, appGitRepoName,
            gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
            accountAppfileMap, appfileStackMap, crNumber,
            templates, secrets)
    }
  }
}

def deploy(gitBranchName, buildNumber, organizationName, appGitRepoName,
          gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
          accountAppfileMap, appfileStackMap, crNumber,
          templates, secrets) {

  def commons = new com.westernasset.pipeline.Commons()

  def cdkBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'cdk', image: "${cdkBuilderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ])  {
    node(POD_LABEL) {
      println 'inside the deployment logic'

      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

      echo currentBuild.displayName

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      def secretRoot = "secret/${organizationName}/${appGitRepoName}/prod"
      echo secretRoot

      def appRoleName = organizationName + '-' + appGitRepoName + '-prod'
      echo appRoleName

      def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, true);
      echo "application vault auth token -> ${appVaultAuthToken}"

      def secretRootBase = "secret/${organizationName}/${appGitRepoName}/prod"

      container('cdk') {
        commons.setNpmrcFilelink()
      }

      commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)

      commons.copySecretsToLocationForCDK(templates, secrets)

      def prodAcc = prodAccounts.replaceAll("\n", ",")
      def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}-${prodAcc}"
      sh """
        git config --global user.email "jenkins@westernasset.com"
        git config --global user.name "Jenkins Agent"
        git config --global http.sslVerify false
        git config --global push.default matching
        git config -l

        ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}"'
        ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
      """

      stage('Synth and Diff for Prod') {
        container('cdk') {
          commons.npmBuild()
          commons.awsCDKSynth(prodAccounts, accountAppfileMap, appfileStackMap)
          commons.awsCDKDiff(prodAccounts, accountAppfileMap, appfileStackMap)
          commons.awsCDKDeploy(prodAccounts, organizationName, appGitRepoName, accountAppfileMap, appfileStackMap)
        }
      }
    }
  }
  rollback(gitBranchName, buildNumber, organizationName, appGitRepoName,
           gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
           accountAppfileMap, appfileStackMap, crNumber)
}

def rollback(gitBranchName, buildNumber, organizationName, appGitRepoName,
             gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
             accountAppfileMap, appfileStackMap, crNumber) {

  def didAbort = false
  def didTimeout = false

  stage("destroy Stacks?") {
    checkpoint "Destroy Stacks"
    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Destroy Stacks?')
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
    derollbackLogic(gitBranchName, buildNumber, organizationName, appGitRepoName,
                    gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
                    accountAppfileMap, appfileStackMap, crNumber)
  }
}

def derollbackLogic(gitBranchName, buildNumber, organizationName, appGitRepoName,
                    gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
                    accountAppfileMap, appfileStackMap, crNumber) {

  def commons = new com.westernasset.pipeline.Commons()

  def cdkBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-prod-stacs-destory"

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'cdk', image: "${cdkBuilderImage}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws'),
        persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
    ]) {
    node(POD_LABEL) {
      try {
        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        container('cdk') {
          commons.setNpmrcFilelink()
        }
        stage('Destory Prod Stacks') {
          container('cdk') {
            commons.npmBuild()
            commons.awsCDKDestroy(prodAccounts, accountAppfileMap, appfileStackMap)
          }
        }

      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
}
