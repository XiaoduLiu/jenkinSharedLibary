package com.westernasset.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          qaEnvs, prodEnv, releaseVersion, templates, secrets,
          startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
          appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
          liquibaseChangeLog, liquibaseBuilderTag, buildSteps, hostDomain, os) {

  def baseDisplayTag
  def organizationName
  def appGitRepoName
  def gitCommit
  def gitScm
  def pomversion
  def appDtrRepo
  def imageTag

  def commons = new com.westernasset.pipeline.Commons()

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
  ]) {
    node(POD_LABEL) {
      def projectType = "${projectTypeParam}"
      currentBuild.displayName = "${gitBranchName}-${buildNumber}"

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

        imageTag = commons.setJobLabelJavaProject(gitBranchName, buildNumber)
        commons.mavenSnapshotBuild()
        commons.archive(appArtifacts)

        build job: "${env.siteDeployJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
          [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
          [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
          [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)]
        ]
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                nonProdEnvs, qaEnvs, prodEnv, releaseVersion, organizationName,
                appGitRepoName, appDtrRepo, gitCommit, templates, secrets,
                startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                appArtifactsRemoteDests, nonProdHostsMap, prodHosts, imageTag, preInstallArtifacts,
                preInstallArtifactsDests, liquibaseChangeLog, liquibaseBuilderTag, buildSteps, hostDomain, os)
}

def nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                  nonProdEnvs, qaEnvs, prodEnv, releaseVersion, organizationName,
                  appGitRepoName, appDtrRepo, gitCommit, templates, secrets,
                  startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                  appArtifactsRemoteDests, nonProdHostsMap, prodHosts, imageTag, preInstallArtifacts,
                  preInstallArtifactsDests, liquibaseChangeLog, liquibaseBuilderTag, buildSteps, hostDomain, os) {
  def commons = new com.westernasset.pipeline.Commons()
  stage("Deploy to non-prod?") {
    checkpoint "Deploy To Non-Prod"

    def didAbort = false
    def didTimeout = false

    def userInput
    def deployEnv
    def releaseFlag

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
      currentBuild.displayName = "${imageTag}-${deployEnv}"
      echo currentBuild.displayName
      nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                         nonProdEnvs, qaEnvs, prodEnv, releaseVersion, organizationName,
                         appGitRepoName, appDtrRepo, gitCommit, templates, secrets,
                         startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                         appArtifactsRemoteDests, nonProdHostsMap, prodHosts, imageTag, preInstallArtifacts,
                         preInstallArtifactsDests, releaseFlag, liquibaseChangeLog, liquibaseBuilderTag,
                         buildSteps, deployEnv, hostDomain, os)
    }
  }
}

def nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                       nonProdEnvs, qaEnvs, prodEnv, releaseVersion, organizationName,
                       appGitRepoName, appDtrRepo, gitCommit, templates, secrets,
                       startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                       appArtifactsRemoteDests, nonProdHostsMap, prodHosts, imageTag, preInstallArtifacts,
                       preInstallArtifactsDests, releaseFlag, liquibaseChangeLog, liquibaseBuilderTag,
                       buildSteps, deployEnv, hostDomain, os) {

  def commons = new com.westernasset.pipeline.Commons()
  def qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
  echo deployEnv
  echo qaEnvs
  echo "qaPassFlag::::"
  if (qaPassFlag) {
    echo "true!!!"
  } else {
    echo "false!!!"
  }

  def tagForApprove

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  def liquibaseImage = "${env.TOOL_BUSYBOX}"
  if (liquibaseBuilderTag != 'null') {
    liquibaseImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${liquibaseBuilderTag}"
  }
  properties([
    copyArtifactPermission('*'),
  ]);
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${env.TOOL_BUSYBOX}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'liquibase', image: "${liquibaseImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
  ]) {
    node(POD_LABEL) {

      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      def projectName = currentBuild.projectName
      println projectName + ":" + buildNumber

      def deployUser = commons.findRemoteAppUser(remoteAppUser, deployEnv)

      copyArtifacts(projectName: env.JOB_NAME, selector: specific("${buildNumber}"))

      def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
      echo secretRoot

      def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'
      echo appRoleName

      def wp = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
      echo wp

      def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, false);
      echo "application vault auth token -> ${appVaultAuthToken}"

      //set application build time environment
      def envMap = [:]
      if (fileExists("${workspace}/conf/env/${deployEnv}.groovy")) {
        echo "Yes, ${workspace}/conf/env/${deployEnv}.groovy exists"
        def myenv = load "${workspace}/conf/env/${deployEnv}.groovy"
        envMap = myenv.getEnvMap()
      }
      echo workspace
      def hostMap = commons.getMapFromString(nonProdHostsMap)

      def hostsString = hostMap["${deployEnv}"]
      echo hostsString

      def domainName = commons.getDomainName(hostDomain, envMap)
      echo domainName

      def sshPrivateKeyRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/ssh-keys/id_rsa"

      container('vault') {
        //processing the private keys
        withEnv(["VAULT_TOKEN=${appVaultAuthToken}",
                 "SSH_PRIVATE_KEY_ROOT=${sshPrivateKeyRoot}"]) {
          sh """
            consul-template -vault-renew-token=false -once -template /home/jenkins/.ssh/id_rsa_vm.ctmpl:$workspace/id_rsa
            chmod 400 ${workspace}/id_rsa
          """
        }
      }

      //processing the application configuration files
      if (secrets != "null") {
        def secretRootBase = "secret/${organizationName}/${appGitRepoName}/nonprod"
        commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
      }

      if (preInstallArtifacts!="null") {
        echo preInstallArtifacts
        echo preInstallArtifactsDests
        commons.deployPreInstallArtifacts(hostsString, deployUser, preInstallArtifacts, preInstallArtifactsDests, domainName, os)
      }

      echo "do the Pre Deployment Execution"
      commons.localBuildSteps("Build & Test", buildSteps)

      stage ('Stop servers') {
        commons.parallelScriptRun(hostsString, deployUser, stopServerScripts, 'stop', domainName)
      }

      if (liquibaseChangeLog != 'null') {
        //do liquibase step
        stage("Liquibase Database Update") {
          echo "EXECUTE LIQUIDBASE"
          echo "${env.WORKSPACE}"
          commons.liquibaseProcess("${env.liquibaseProjectFolder}", workspace, liquibaseChangeLog,
                                   secretRoot, appRoleName, false, 'null', 'non-prod')
        }
      }

      stage ("Deploy" ) {
        commons.deployArtifacts(hostsString, deployUser, secrets, secretsRemoteDests, appArtifacts, appArtifactsRemoteDests, deployEnv, domainName, os)
      }
      stage ('Start servers') {
        echo hostMap["${deployEnv}"]
        commons.parallelScriptRun(hostsString, deployUser, startServerScripts, 'start', domainName)
      }
      //echo buildNumber
      if (qaPassFlag) {
        stage('Build Release') {
          def releaseBuild = new com.westernasset.pipeline.devRelease.releaseBuild_mavenSSH()
          tagForApprove = releaseBuild.build(projectTypeParam, gitBranchName, buildNumber,  builderTag, gitScm,
                                             gitCommit, appDtrRepo, organizationName, appGitRepoName, prodEnv,
                                             releaseVersion, templates, secrets, startServerScripts, stopServerScripts,
                                             remoteAppUser, secretsRemoteDests, appArtifacts, appArtifactsRemoteDests, nonProdHostsMap,
                                             prodHosts, preInstallArtifacts, preInstallArtifactsDests)
        }
      } else {
        echo 'Deploy to QA and click on the release checkbox to trigger the build release!!!'
      }
    }
  }
  if (tagForApprove != null) {
    qaApproveLogic(projectTypeParam, gitBranchName, buildNumber,  builderTag, gitScm,
                   gitCommit, appDtrRepo, organizationName, appGitRepoName, prodEnv,
                   releaseVersion, templates, secrets, startServerScripts, stopServerScripts,
                   remoteAppUser, secretsRemoteDests, appArtifacts, appArtifactsRemoteDests, nonProdHostsMap,
                   prodHosts, preInstallArtifacts, preInstallArtifactsDests, tagForApprove, liquibaseChangeLog,
                   liquibaseBuilderTag, buildSteps, hostDomain, os)
  }
}

def qaApproveLogic(projectTypeParam, gitBranchName, buildNumber,  builderTag, gitScm,
               gitCommit, appDtrRepo, organizationName, appGitRepoName, prodEnv,
               releaseVersion, templates, secrets, startServerScripts, stopServerScripts,
               remoteAppUser, secretsRemoteDests, appArtifacts, appArtifactsRemoteDests, nonProdHostsMap,
               prodHosts, preInstallArtifacts, preInstallArtifactsDests, tagForApprove, liquibaseChangeLog,
               liquibaseBuilderTag, buildSteps, hostDomain, os) {

  stage("QA Approve?") {
    checkpoint "QA Approve"

    currentBuild.displayName = tagForApprove

    def didTimeout = false
    def userInput

    def parentDisplayName = currentBuild.rawBuild.getParent().getFullName()
    println "Parent = " + parentDisplayName

    try {
      timeout(time: 60, unit: 'SECONDS') {
        userInput = input(
          id: 'userInput', message: 'Approve Release?', parameters: [
          [$class: 'TextParameterDefinition', defaultValue: '', description: 'CR Number', name: 'crNumber']
        ])
        echo ("CR Number: "+userInput)
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }

    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      stage("QA approve") {
        echo "Approle release for CR ${userInput}"
        echo "Trigger the production release task!!!"

        currentBuild.displayName = tagForApprove + '-' + userInput

        stage('trigger downstream job') {
          echo buildNumber
          echo gitBranchName
          echo gitCommit
          echo gitScm
          echo appDtrRepo
          echo organizationName
          echo appGitRepoName
          echo prodEnv
          echo 'tagForApprove=' + tagForApprove

          build job: "${env.opsReleaseJob}", wait: false, parameters: [
            [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
            [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(tagForApprove)],
            [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(userInput)],
            [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
            [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
            [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
            [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
            [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
            [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
            [$class: 'StringParameterValue', name: 'prodEnv', value: String.valueOf(prodEnv)],
            [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: String.valueOf(liquibaseChangeLog)],
            [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: String.valueOf(liquibaseBuilderTag)],
            [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
            [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
            [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
            [$class: 'StringParameterValue', name: 'startServerScripts', value: String.valueOf(startServerScripts)],
            [$class: 'StringParameterValue', name: 'stopServerScripts', value: String.valueOf(stopServerScripts)],
            [$class: 'StringParameterValue', name: 'remoteAppUser', value: String.valueOf(remoteAppUser)],
            [$class: 'StringParameterValue', name: 'secretsRemoteDests', value: String.valueOf(secretsRemoteDests)],
            [$class: 'StringParameterValue', name: 'appArtifacts', value: String.valueOf(appArtifacts)],
            [$class: 'StringParameterValue', name: 'appArtifactsRemoteDests', value: String.valueOf(appArtifactsRemoteDests)],
            [$class: 'StringParameterValue', name: 'nonProdHostsMap', value: String.valueOf(nonProdHostsMap)],
            [$class: 'StringParameterValue', name: 'prodHosts', value: String.valueOf(prodHosts)],
            [$class: 'StringParameterValue', name: 'preInstallArtifacts', value: String.valueOf(preInstallArtifacts)],
            [$class: 'StringParameterValue', name: 'preInstallArtifactsDests', value: String.valueOf(preInstallArtifactsDests)],
            [$class: 'StringParameterValue', name: 'upstreamJobName', value: String.valueOf(env.JOB_NAME)],
            [$class: 'StringParameterValue', name: 'upstreamBuildNumber', value: String.valueOf(env.BUILD_NUMBER)],
            [$class: 'StringParameterValue', name: 'buildSteps', value: String.valueOf(buildSteps)],
            [$class: 'StringParameterValue', name: 'hostDomain', value: String.valueOf(hostDomain)],
            [$class: 'StringParameterValue', name: 'os', value: String.valueOf(os)]
          ]
        }
      }
    }
  }
}
