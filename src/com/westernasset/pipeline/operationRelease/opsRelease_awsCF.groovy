package com.westernasset.pipeline.operationRelease;

def build(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
          gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
          appfileStackMap, crNumber, parametersOverridesMap) {

  def commons = new com.westernasset.pipeline.Commons()

  def awsBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'aws', image: "${awsBuilderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws')
  ])  {
    node(POD_LABEL) {
      println 'inside the deployment logic'

      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

      echo currentBuild.displayName

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      stage("Dry Run") {
        container('aws') {
          commons.awsCFDryrun(prodAccounts, accountAppfileMap, appfileStackMap, parametersOverridesMap, organizationName, appGitRepoName)
        }
      }
    }
  }
  approveDeploy(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
                gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
                appfileStackMap, crNumber, parametersOverridesMap)
}

def approveDeploy(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
                  gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
                  appfileStackMap, crNumber, parametersOverridesMap) {

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
      deploy(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
             gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
             appfileStackMap, stackBudgetCodeMap, crNumber, parametersOverridesMap)
    }
  }
}

def deploy(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
           gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
           appfileStackMap, crNumber, parametersOverridesMap) {

  def commons = new com.westernasset.pipeline.Commons()

  def awsBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'aws', image: "${awsBuilderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh')
  ])  {
    node(POD_LABEL) {
      println 'inside the deployment logic'

      currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

      echo currentBuild.displayName

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

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

      stage('Deploy to Prod') {
        container('aws') {
          def sa = prodAccounts.split('\n')
          def dem = sa.length
          def i = 0
          while (i < dem) {
            echo "i=" + i
            def profile = sa[i]
            commons.awsCFDeploy(accountAppfileMap, appfileStackMap, parametersOverridesMap, organizationName, appGitRepoName, profile)
            i=i+1
          }
        }
      }
    }
  }
  rollback(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
           gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
           appfileStackMap, crNumber, parametersOverridesMap)
}

def rollback(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
             gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
             appfileStackMap, crNumber, parametersOverridesMap) {

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
    derollbackLogic(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
                    gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
                    appfileStackMap, crNumber, parametersOverridesMap)
  }
}

def derollbackLogic(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
                    gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
                    appfileStackMap, crNumber, parametersOverridesMap) {

  def commons = new com.westernasset.pipeline.Commons()

  def awsBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-prod-stacs-destory"

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'aws', image: "${awsBuilderImage}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws')
    ]) {
    node(POD_LABEL) {
      try {
        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        stage('Destory Prod Stacks') {
          container('aws') {
            def sa = prodAccounts.split('\n')
            def dem = sa.length
            def i = 0
            while (i < dem) {
              echo "i=" + i
              def profile = sa[i]
              commons.awsCFDeploy(accountAppfileMap, appfileStackMap, parametersOverridesMap, organizationName, appGitRepoName, profile)
              i=i+1
            }
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
