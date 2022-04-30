package com.westernasset.pipeline.operationRelease;

def build(gitBranchName, buildNumber, imageTags, gitCommit, gitScm,
          organizationName, appGitRepoName, templates, secrets, appDtrRepo,
          startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
          appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
          upstreamJobName, upstreamBuildNumber, crNumber, releaseVersion,  buildSteps,
          preStartServerLocalCmds, postStartServerLocalCmds, builderTag, backupDest, projectType,
          liquibaseChangeLog, liquibaseBuilderTag, hostDomain, os) {
  def commons = new com.westernasset.pipeline.Commons()
  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
    def userInput
    if (projectType == 'SSDeploy') {
      currentBuild.displayName = gitBranchName + '-' + releaseVersion + '-' + buildNumber + '-' + crNumber
    } else {
      currentBuild.displayName = releaseVersion + '-' + crNumber
    }
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
      SSHLogicImpl(gitBranchName, buildNumber, imageTags, gitCommit, gitScm,
                  organizationName, appGitRepoName, templates, secrets, appDtrRepo,
                  startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                  appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
                  upstreamJobName, upstreamBuildNumber, crNumber, releaseVersion,  buildSteps,
                  preStartServerLocalCmds, postStartServerLocalCmds, builderTag, backupDest, projectType,
                  liquibaseChangeLog, liquibaseBuilderTag, hostDomain, os)
    }
  }
}

def SSHLogicImpl(gitBranchName, buildNumber, imageTags, gitCommit, gitScm,
                organizationName, appGitRepoName, templates, secrets, appDtrRepo,
                startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
                upstreamJobName, upstreamBuildNumber, crNumber, releaseVersion,  buildSteps,
                preStartServerLocalCmds, postStartServerLocalCmds, builderTag, backupDest, projectType,
                liquibaseChangeLog, liquibaseBuilderTag, hostDomain, os) {

  def commons = new com.westernasset.pipeline.Commons()
  def builderImage = "${env.TOOL_BUSYBOX}"
  def deployUser

  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  }
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
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", command: 'cat', ttyEnabled: true),
      containerTemplate(name: 'liquibase', image: "${liquibaseImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-prod', mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
  ]) {

    node(POD_LABEL) {

      echo crNumber
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        if (projectType == 'SSDeploy') {
          currentBuild.displayName = gitBranchName + '-' + releaseVersion + '-' + buildNumber + '-' + crNumber + '-' + prodHosts
        } else {
          currentBuild.displayName = gitBranchName + '-' + releaseVersion + '-' + crNumber + '-' + prodHosts
        }

        deployUser = commons.findRemoteAppUser(remoteAppUser, 'prod')

        def secretRoot = "secret/${organizationName}/${appGitRepoName}/prod"
        echo secretRoot

        def appRoleName = organizationName + '-' + appGitRepoName + '-prod'
        echo appRoleName

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        echo workspace

        def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, true);
        echo "application vault auth token -> ${appVaultAuthToken}"

        def sshPrivateKeyRoot = "secret/${organizationName}/${appGitRepoName}/prod/ssh-keys/id_rsa"

        //processing the private keys
        container('vault') {
          withEnv(["VAULT_TOKEN=${appVaultAuthToken}",
                   "SSH_PRIVATE_KEY_ROOT=${sshPrivateKeyRoot}"]) {
            sh """
              consul-template -vault-renew-token=false -once -template /home/jenkins/.ssh/id_rsa_vm.ctmpl:$workspace/id_rsa
              chmod 400 ${workspace}/id_rsa
              ls -la
            """
          }
        }

        //load the environment map (environment specific scripts or settings)
        def envMap = commons.getEnironmentMap('prod')

        def domainName = 'westernasset.com'
        if (projectType == 'mavenSSHDeploy') {
          //set application build time environment
          def mavenEnvMap = [:]
          if (fileExists("${workspace}/conf/env/prod.groovy")) {
            echo "Yes, ${workspace}/conf/env/prod.groovy exists"
            def mavenEnvMapObj = load "${workspace}/conf/env/prod.groovy"
            mavenEnvMap = mavenEnvMapObj.getEnvMap()
            println mavenEnvMap
          }
          domainName = commons.getDomainName(hostDomain, mavenEnvMap)
          echo '--------=' + domainName
        } else {
          echo '=========' + domainName
        }

        //processing the application configuration files
        if (secrets != "null") {
          def secretRootBase = "secret/${organizationName}/${appGitRepoName}/prod"
          commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
        }

        echo workspace

        if (projectType != 'SSHDeploy') {
          //copy appArtifacts
          def appArtifactsEnv = commons.getScriptsByName(envMap, 'appArtifacts')
          appArtifactsEnv = (appArtifactsEnv != 'null')?appArtifactsEnv : appArtifacts
          commons.copyArtifactsFromUpstream(upstreamJobName, upstreamBuildNumber, appArtifactsEnv)
        }

        if (preInstallArtifacts!="null") {
          echo preInstallArtifacts
          echo preInstallArtifactsDests
          commons.deployPreInstallArtifacts(prodHosts, deployUser, preInstallArtifacts, preInstallArtifactsDests, domainName, os)
        }

        echo "do the Pre Deployment Execution"
        def buildStepsEnv = commons.getScriptsByName(envMap, 'buildSteps')
        buildStepsEnv = (buildStepsEnv != 'null')? buildStepsEnv : buildSteps
        commons.localBuildSteps("Build & Test", buildStepsEnv)

        if (stopServerScripts != 'null') {
          stage ('Stop the servers') {
            commons.parallelScriptRun(prodHosts, deployUser, stopServerScripts, 'stop', domainName)
          }
        }

        if (liquibaseChangeLog != 'null') {
          //do liquibase step
          stage("Liquibase Database Update") {
            echo "EXECUTE LIQUIDBASE"
            echo "${env.WORKSPACE}"
            commons.liquibaseProcess("${env.liquibaseProjectFolder}", workspace, liquibaseChangeLog,
                                     secretRoot, appRoleName, true, 'null', projectType)
          }
        }

        def appArtifactsRemoteDestsEnv = commons.getScriptsByName(envMap, 'appArtifactsRemoteDests')
        appArtifactsRemoteDestsEnv = (appArtifactsRemoteDestsEnv != 'null')? appArtifactsRemoteDestsEnv : appArtifactsRemoteDests
        if (backupDest != 'null') {
          stage ('Backup Resources') {
            commons.backupResourceForRollback(prodHosts, deployUser, appArtifactsRemoteDestsEnv, backupDest)
          }
        }


        stage ('Deploy to Prod') {
          commons.deployArtifacts(prodHosts, deployUser, secrets, secretsRemoteDests, appArtifacts, appArtifactsRemoteDests, 'prod', domainName, os)
        }

        echo "Pre Start Server steps"
        def preStartServerLocalCmdsEnv = commons.getScriptsByName(envMap, 'preStartServerLocalCmds')
        preStartServerLocalCmdsEnv = (preStartServerLocalCmdsEnv != 'null')? preStartServerLocalCmdsEnv : preStartServerLocalCmds
        commons.localBuildSteps("Pre Start Sever Steps", preStartServerLocalCmdsEnv)

        def startServerScriptsEnv = commons.getScriptsByName(envMap, 'startServerScripts')
        startServerScriptsEnv = (startServerScriptsEnv != 'null')? startServerScriptsEnv : startServerScripts
        if (startServerScriptsEnv != 'null') {
          stage ('Start the servers') {
            commons.parallelScriptRun(prodHosts, deployUser, startServerScriptsEnv, 'start', domainName)
          }
        }

        echo "Post Start Server steps"
        commons.localBuildSteps("Post Start Server Steps", postStartServerLocalCmds)

      } catch (err) {
  	    currentBuild.result = 'FAILED'
  	    throw err
  	  }
    }
  }
  if (backupDest != 'null') {
    stageProductionRollback(gitBranchName, buildNumber, imageTags, gitCommit, gitScm,
                            organizationName, appGitRepoName, templates, secrets, appDtrRepo,
                            startServerScripts, stopServerScripts, deployUser, secretsRemoteDests, appArtifacts,
                            appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
                            upstreamJobName, upstreamBuildNumber, crNumber, releaseVersion,  buildSteps,
                            preStartServerLocalCmds, postStartServerLocalCmds, builderTag, backupDest, projectType)
  }
}

def stageProductionRollback(gitBranchName, buildNumber, imageTags, gitCommit, gitScm,
                            organizationName, appGitRepoName, templates, secrets, appDtrRepo,
                            startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                            appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
                            upstreamJobName, upstreamBuildNumber, crNumber, releaseVersion,  buildSteps,
                            preStartServerLocalCmds, postStartServerLocalCmds, builderTag, backupDest, projectType) {

  stage("Production Rollback?") {
    checkpoint "productionRollback"

    def didTimeout = false
    def userInput

    if (projectType == 'SSDeploy') {
      currentBuild.displayName = gitBranchName + '-' + releaseVersion + '-' + buildNumber + '-' + crNumber
    } else {
      currentBuild.displayName =  gitBranchName + '-' + releaseVersion + '-' + crNumber
    }

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
      prodDeployRollbackLogic(gitBranchName, buildNumber, imageTags, gitCommit, gitScm,
                              organizationName, appGitRepoName, templates, secrets, appDtrRepo,
                              startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                              appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
                              upstreamJobName, upstreamBuildNumber, crNumber, releaseVersion,  buildSteps,
                              preStartServerLocalCmds, postStartServerLocalCmds, builderTag, backupDest, projectType)
    }
  }
}


def prodDeployRollbackLogic(gitBranchName, buildNumber, imageTags, gitCommit, gitScm,
                            organizationName, appGitRepoName, templates, secrets, appDtrRepo,
                            startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                            appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
                            upstreamJobName, upstreamBuildNumber, crNumber, releaseVersion,  buildSteps,
                            preStartServerLocalCmds, postStartServerLocalCmds, builderTag, backupDest, projectType) {
  def commons = new com.westernasset.pipeline.Commons()
  properties([
    copyArtifactPermission('*'),
  ]);
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    containers: [
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
  ]) {
    node(POD_LABEL) {
      // Clean workspace before doing anything
      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      if (projectType == 'SSHDeploy') {
        currentBuild.displayName = gitBranchName + '-' + releaseVersion + '-' + buildNumber + '-' + crNumber + '-' + prodHosts + "-rollback"
      } else {
        currentBuild.displayName = gitBranchName + '-'+ releaseVersion + '-' + crNumber + '-' + prodHosts + "-rollback"
      }
      def secretRoot = "secret/${organizationName}/${appGitRepoName}/prod"
      echo secretRoot

      def appRoleName = organizationName + '-' + appGitRepoName + '-prod'
      echo appRoleName

      def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
      echo workspace

      def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, true);
      echo "application vault auth token -> ${appVaultAuthToken}"

      def sshPrivateKeyRoot = "secret/${organizationName}/${appGitRepoName}/prod/ssh-keys/id_rsa"

      //processing the private keys
      container('vault') {
        withEnv(["VAULT_TOKEN=${appVaultAuthToken}",
                 "SSH_PRIVATE_KEY_ROOT=${sshPrivateKeyRoot}"]) {
          sh """
            consul-template -vault-renew-token=false -once -template /home/jenkins/.ssh/id_rsa_vm.ctmpl:$workspace/id_rsa
            chmod 400 ${workspace}/id_rsa
            ls -la
          """
        }
      }

      commons.restoreResourceForRollback(prodHosts, remoteAppUser, appArtifactsRemoteDests, backupDest)

    }
  }
}
