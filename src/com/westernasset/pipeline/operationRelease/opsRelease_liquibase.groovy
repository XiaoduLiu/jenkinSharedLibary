package com.westernasset.pipeline.operationRelease;

def build(gitScm, gitBranchName, gitCommit, crNumber, liquibaseChangeLog,
          liquibaseBuilderTag, organizationName, appGitRepoName, releaseVersion, buildNumber,
          appDtrRepo, projectType, imageTags, templates) {
  def appRoleName = organizationName + '-' + appGitRepoName + '-prod'

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
    def userInput

    currentBuild.displayName = releaseVersion + '-' + crNumber
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
      liquibaseLogicImpl(gitScm, gitBranchName, gitCommit,
                         crNumber, liquibaseChangeLog, liquibaseBuilderTag, organizationName, appGitRepoName,
                         releaseVersion, buildNumber, appDtrRepo, projectType, imageTags, templates)
    }
  }
}

def liquibaseLogicImpl(gitScm, gitBranchName, gitCommit,
                       crNumber, liquibaseChangeLog, liquibaseBuilderTag, organizationName, appGitRepoName,
                       releaseVersion, buildNumber, appDtrRepo, projectType, imageTags, templates) {

  def commons = new com.westernasset.pipeline.Commons()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${liquibaseBuilderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'liquibase', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh')
  ]) {
    node(POD_LABEL) {
    try {
      // Clean workspace before doing anything
      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      echo liquibaseChangeLog

      def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
      echo workspace

      def secretRoot="secret/${organizationName}/${appGitRepoName}/prod"
      echo secretRoot

      def appRoleName = organizationName + '-' + appGitRepoName + '-prod'

      if (liquibaseChangeLog != 'null') {
        //do liquibase step
        stage("Liquibase Database Update") {
          echo "EXECUTE LIQUIDBASE"
          def liquibaseRollbackTag = "${releaseVersion}-${crNumber}"

          commons.processLiquibaseTemplates(templates, 'prod')
          commons.liquibaseProcess('null', workspace, liquibaseChangeLog, secretRoot, appRoleName,
                                   true, liquibaseRollbackTag, projectType)

          //If project type is liquibase, it will trigger a rollback secondary job
          if(projectType == 'liquibase') {
            build job: "${env.opsLiquibaseRollback}", wait: false, parameters: [
              [$class: 'StringParameterValue', name: 'buildNumber', value: buildNumber],
              [$class: 'StringParameterValue', name: 'releaseVersion', value: releaseVersion],
              [$class: 'StringParameterValue', name: 'crNumber', value: crNumber],
              [$class: 'StringParameterValue', name: 'gitBranchName', value: gitBranchName],
              [$class: 'StringParameterValue', name: 'gitCommit', value: gitCommit],
              [$class: 'StringParameterValue', name: 'gitScm', value: gitScm],
              [$class: 'StringParameterValue', name: 'organizationName', value: organizationName],
              [$class: 'StringParameterValue', name: 'appGitRepoName', value: appGitRepoName],
              [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: liquibaseChangeLog],
              [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: liquibaseBuilderTag],
              [$class: 'StringParameterValue', name: 'liquibaseRollbackTag', value: liquibaseRollbackTag],
              [$class: 'StringParameterValue', name: 'templates', value: templates]
            ]
          }
        }
      }
    } catch (err) {
      currentBuild.result = 'FAILED'
      throw err
    }
  }
  }
}
