package com.westernasset.pipeline.operationRelease;

def build(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag, appPath, module, fromEnv, toEnv, releaseVersion, gitCommit, gitScm, crNumber) {

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
    def userInput

    currentBuild.displayName = releaseVersion

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
      databricksWorkbookCopyImpl(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag, appPath, module, fromEnv, toEnv, releaseVersion, gitCommit, gitScm, crNumber)
    }
  }
}

def databricksWorkbookCopyImpl(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag, appPath, module, fromEnv, toEnv, releaseVersion, gitCommit, gitScm, crNumber) {
  def commons = new com.westernasset.pipeline.Commons()
  def databrickImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'databrick', image: "${databrickImage}", ttyEnabled: true)],
    volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
    ]) {
    node(POD_LABEL) {
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        echo workspace

        container('databrick') {
          withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.DATABRICK_JENKINS}",
            usernameVariable: 'DATABRICKS_HOST', passwordVariable: 'DATABRICKS_TOKEN']]) {

            try {
              sh """
                databricks workspace rm -r $appPath/$toEnv/$module
              """
            } catch(err) {
              print err.getMessage()
            }

            sh """
              rm -rf ./$appPath/$toEnv/$module
              mkdir -p ./$appPath/$toEnv/$module
              cp -a ./$appPath/$fromEnv/$module/. ./$appPath/$toEnv/$module

              databricks workspace import_dir -o ./$appPath/$toEnv/$module $appPath/$toEnv/$module
            """
          }

        }

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
}
