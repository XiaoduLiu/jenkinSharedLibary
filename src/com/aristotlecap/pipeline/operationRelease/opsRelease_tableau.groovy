package com.aristotlecap.pipeline.operationRelease;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
          tdsxFiles, tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
          organizationName, appGitRepoName, gitScm, gitCommit, twbProjects,
          crNumber, tabbedFlag, twbSecrets, deleteNames, deleteFromProjects) {

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
             tdsxFiles, tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
             organizationName, appGitRepoName, gitScm, gitCommit, twbProjects,
             crNumber, tabbedFlag, twbSecrets, deleteNames, deleteFromProjects)
    }
  }
}

def deploy(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion,
           tdsxFiles, tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
           organizationName, appGitRepoName, gitScm, gitCommit, twbProjects,
           crNumber, tabbedFlag, twbSecrets, deleteNames, deleteFromProjects) {

  def commons = new com.aristotlecap.pipeline.Commons()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'tableau', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh')
  ])  {
    node(POD_LABEL) {
      try {
        println 'inside the deployment logic'

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
        stage('Deploy to Prod') {
          boolean bvar = Boolean.parseBoolean(tabbedFlag)
          commons.processTableauResource(tdsxFiles, tdsxNames, tdsxSecrets, tdsxProjects, 'tdsx', 'prod', bvar, organizationName, appGitRepoName)
          commons.processTableauResource(twbFiles, twbNames, twbSecrets, twbProjects, 'twb', 'prod', bvar, organizationName, appGitRepoName)
          commons.processTableauResource('null', deleteNames, 'null', deleteFromProjects, 'delete', 'prod', 'null', organizationName, appGitRepoName)
        }

      } catch(err) {
        println err
      }
    }
  }
}
