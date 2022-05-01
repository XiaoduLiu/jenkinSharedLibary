#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  stage("Should I Proceed Database Rollback?") {
    checkpoint "proddeploy"

    def didTimeout = false
    def userInput

    currentBuild.displayName = params.liquibaseRollbackTag

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Rollback?')
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }
    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      lock("${params.prodEnv}") {
         snapshotRollbackLogic(
          "${params.gitScm}",
          "${params.gitBranchName}",
          "${params.gitCommit}",
          "${params.liquibaseChangeLog}",
          "${params.liquibaseBuilderTag}",
          "${params.organizationName}",
          "${params.appGitRepoName}",
          "${params.deployEnv}",
          "${params.liquibaseRollbackTag}",
          "${params.templates}"
        )
      }
    }
  }
}

def snapshotRollbackLogic(gitScm, gitBranchName, gitCommit, liquibaseChangeLog, liquibaseBuilderTag,
                          organizationName, appGitRepoName,deployEnv,liquibaseRollbackTag, templates) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'

  def label = "agent-${UUID.randomUUID().toString()}"
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${liquibaseBuilderTag}"

  podTemplate(
    label: label,
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
    node(label) {
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"

        sh "git reset --hard ${gitCommit}"

        echo liquibaseChangeLog

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        echo workspace

        if (liquibaseChangeLog != 'null') {
          def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
          echo secretRoot

          //do liquibase step
          stage("Liquibase Database Update") {
            echo "EXECUTE LIQUIDBASE IN snapshotRollbackLogic"

            println "templates->" + templates
            println "deployEnv->" + deployEnv
            
            commons.processLiquibaseTemplates(templates, deployEnv)
            commons.liquibaseRollbackProcess(workspace,
                                             liquibaseChangeLog,
                                             liquibaseBuilderTag,
                                             secretRoot,
                                             appRoleName,
                                             liquibaseRollbackTag,
                                             false)
          }
        }
  	  } catch (err) {
  	    currentBuild.result = 'FAILED'
  	    throw err
  	  }
    }
  }
}
