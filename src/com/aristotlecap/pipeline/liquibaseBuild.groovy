package com.aristotlecap.pipeline;

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*

def build(projectTypeParam, gitBranchName, buildNumber, nonProdEnvs, liquibaseChangeLog,
          liquibaseBuilderTag, qaEnvs, releaseVersion, templates) {

  def gitCommit
  def pomversion

  def projectType = "${projectTypeParam}"
  def imageTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = imageTag

  def organizationName
  def appGitRepoName
  def appDtrRepo
  def gitScm

  def commons = new com.aristotlecap.pipeline.Commons()

  podTemplate(
    cloud: 'pas-development',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}')
    ],
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins') {

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

          appDtrRepo = organizationName + '/' + appGitRepoName
          echo "appDtrRepo -> ${appDtrRepo}"
        }

        imageTag = commons.setJobLabelNonJavaProject(gitBranchName, gitCommit, buildNumber, releaseVersion)

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }

  nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, gitScm,
                nonProdEnvs, liquibaseChangeLog, liquibaseBuilderTag,
                releaseVersion, organizationName, appGitRepoName, appDtrRepo,
                gitCommit, imageTag, qaEnvs, templates)

}

def nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, gitScm,
                  nonProdEnvs, liquibaseChangeLog, liquibaseBuilderTag,
                  releaseVersion, organizationName, appGitRepoName, appDtrRepo,
                  gitCommit, imageTag, qaEnvs, templates) {

  stage("Should I deploy to Non-Prod?") {
    checkpoint "Deploy To Non-Prod"

    def didAbort = false
    def didTimeout = false

    def userInput
    def deployEnv
    def releaseFlag

    currentBuild.displayName = imageTag

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
      def user = err.getCauses()[0].getUser()
      if('SYSTEM' == user.toString()) { // SYSTEM means timeout.
        didTimeout = true
      } else {
        didAbort = true
        echo "Aborted by: [${user}]"
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
      currentBuild.displayName = imageTag + '-' + deployEnv
      echo currentBuild.displayName

      nonProdDeployLogic(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                         deployEnv, organizationName, appGitRepoName, liquibaseChangeLog,
                         "${env.liquibaseProjectFolder}", liquibaseBuilderTag, imageTag,
                         releaseFlag, nonProdEnvs, qaEnvs,
                         releaseVersion, appDtrRepo, currentBuild.displayName, templates
      )
    }
  }
}

def nonProdDeployLogic(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                       deployEnv, organizationName, appGitRepoName, liquibaseChangeLog,
                       liquibaseProjectFolder, liquibaseBuilderTag, imageTag,
                       releaseFlag, nonProdEnvs, qaEnvs,
                       releaseVersion, appDtrRepo, baseDisplayTag, templates) {

  def qaPassFlag = false
  def snapshotLiquibaseRollbackTag
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${liquibaseBuilderTag}"

  def prompt = new Prompt()

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'liquibase', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
  ]) {
    node(POD_LABEL) {
      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      sh 'ls -la'

      def commons = new com.aristotlecap.pipeline.Commons()
      qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
      echo "qaPassFlag::::"
      if (qaPassFlag) {
        echo "true!!!"
      } else {
        echo "false!!!"
      }
      snapshotLiquibaseRollbackTag = imageTag + '-' + deployEnv
      stage("Liquibase Database Update") {
        echo "EXECUTE LIQUIDBASE"
        echo "${env.WORKSPACE}"


        def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        echo workspace
        def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'

        commons.processLiquibaseTemplates(templates, deployEnv)
        commons.liquibaseProcess(liquibaseProjectFolder, workspace, liquibaseChangeLog, secretRoot,
                                 appRoleName, false, snapshotLiquibaseRollbackTag, projectTypeParam)
      }
    }
  }
  stage("Trigger Downstream Job") {
    if (qaPassFlag) {

      String changeRequest = prompt.changeRequest()

      if (!changeRequest || changeRequest.isEmpty()) {
        return // Stop if timeout or change request not set
      }

      currentBuild.displayName = "${imageTag}-${changeRequest}"

      build job: "${env.opsReleaseJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'projectType', value: 'liquibase'],
        [$class: 'StringParameterValue', name: 'gitScm', value: gitScm],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: gitBranchName],
        [$class: 'StringParameterValue', name: 'gitCommit', value: gitCommit],
        [$class: 'StringParameterValue', name: 'buildNumber', value: buildNumber],
        [$class: 'StringParameterValue', name: 'appDtrRepo', value: appDtrRepo],
        [$class: 'StringParameterValue', name: 'releaseVersion', value: releaseVersion],
        [$class: 'StringParameterValue', name: 'organizationName', value: organizationName],
        [$class: 'StringParameterValue', name: 'appGitRepoName', value: appGitRepoName],
        [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: liquibaseChangeLog],
        [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: liquibaseBuilderTag],
        [$class: 'StringParameterValue', name: 'templates', value: templates],
        [$class: 'StringParameterValue', name: 'crNumber', value: changeRequest]
      ]
    }

    println "templates ->" + templates

    println "${env.snapshotLiquibaseRollback}"

    build job: "${env.snapshotLiquibaseRollback}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'gitBranchName', value: gitBranchName],
      [$class: 'StringParameterValue', name: 'gitCommit', value: gitCommit],
      [$class: 'StringParameterValue', name: 'gitScm', value: gitScm],
      [$class: 'StringParameterValue', name: 'organizationName', value: organizationName],
      [$class: 'StringParameterValue', name: 'appGitRepoName', value: appGitRepoName],
      [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: liquibaseChangeLog],
      [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: liquibaseBuilderTag],
      [$class: 'StringParameterValue', name: 'deployEnv', value: deployEnv],
      [$class: 'StringParameterValue', name: 'liquibaseRollbackTag', value: snapshotLiquibaseRollbackTag],
      [$class: 'StringParameterValue', name: 'templates', value: templates]
    ]
  }
}
