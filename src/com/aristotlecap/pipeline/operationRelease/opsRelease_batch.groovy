package com.aristotlecap.pipeline.operationRelease;

def build(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
          crNumber, organizationName, appGitRepoName,
          releaseVersion, buildNumber, appDtrRepo, projectType, templates,
          secrets, imageTags) {

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
    def userInput

    currentBuild.displayName = params.releaseVersion + '-' + params.crNumber
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
      batchLogicImpl(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
                     crNumber, organizationName, appGitRepoName,
                     releaseVersion, buildNumber, appDtrRepo, projectType, templates,
                     secrets, imageTags)
    }
  }
}

def batchLogicImpl(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
                   crNumber, organizationName, appGitRepoName,
                   releaseVersion, buildNumber, appDtrRepo, projectType, templates,
                   secrets, imageTags) {

  def commons = new com.aristotlecap.pipeline.Commons()
  def appRoleName = organizationName + '-' + appGitRepoName + '-prod'

  echo prodEnv
  def prodCloud = commons.getProdCluster(prodEnv);
  podTemplate(
    cloud: "${prodCloud}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh')
  ]) {
    node(POD_LABEL) {
      echo prodEnv
      echo crNumber
      echo "env ---> ${prodEnv}"
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        echo workspace

        stage("Secret Processing Prod") {
          currentBuild.displayName = releaseVersion + '-' + crNumber + '-' + prodEnv

          echo buildNumber

          def repoNameLower = appGitRepoName.toLowerCase()
          if (repoNameLower.contains('.')) {
             repoNameLower = repoNameLower.replace('.', '-')
          }
          def productionImageTag = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${releaseVersion}-${crNumber}"
          echo productionImageTag

          //sh 'set'
          commons.secretsProcessingForBatchJobs('prod', organizationName, appGitRepoName, templates, secrets, releaseVersion, false, true)
        }
  	  } catch (err) {
  	    currentBuild.result = 'FAILED'
  	    throw err
  	  }
    }
  }

  echo drEnv
  def drCloud = commons.getProdCluster(drEnv);
  podTemplate(
    cloud: "${drCloud}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh')
  ]) {
    node(POD_LABEL) {
      echo prodEnv
      echo crNumber
      echo "env ---> ${prodEnv}"
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        echo workspace

        stage("Secret Processing DR") {
          currentBuild.displayName = releaseVersion + '-' + crNumber + '-' + prodEnv

          echo buildNumber

          def repoNameLower = appGitRepoName.toLowerCase()
          if (repoNameLower.contains('.')) {
             repoNameLower = repoNameLower.replace('.', '-')
          }
          def productionImageTag = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${releaseVersion}-${crNumber}"
          echo productionImageTag

          //sh 'set'
          commons.secretsProcessingForBatchJobs('prod', organizationName, appGitRepoName, templates, secrets, productionImageTag, true, true)
        }
  	  } catch (err) {
  	    currentBuild.result = 'FAILED'
  	    throw err
  	  }
    }
  }



}
