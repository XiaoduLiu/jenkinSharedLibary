package com.aristotlecap.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, nonProdEnvs, qaEnvs,
          nonProdHostsMap, prodHosts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests,
          templates, secrets, secretsRemoteDests, stopServerScripts, startServerScripts,
          appArtifacts, appArtifactsRemoteDests, releaseVersion, buildSteps, preStartServerLocalCmds,
          postStartServerLocalCmds, builderTag, backupDest) {

  def baseDisplayTag
  def organizationName
  def appGitRepoName
  def gitCommit
  def gitScm
  def pomversion

  podTemplate(
    cloud: 'pas-development',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}')
    ],
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins') {

    node(POD_LABEL) {

      def projectType = "${projectTypeParam}"
      currentBuild.displayName = "${gitBranchName}-${buildNumber}"

      def commons = new com.aristotlecap.pipeline.Commons()

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

        }

        baseDisplayTag = commons.setJobLabelNonJavaProject(gitBranchName, gitCommit, buildNumber, releaseVersion)
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, gitScm, nonProdEnvs,
                qaEnvs, releaseVersion, organizationName, appGitRepoName, gitCommit,
                templates, secrets, secretsRemoteDests, startServerScripts, stopServerScripts,
                remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts, appArtifactsRemoteDests,
                nonProdHostsMap, prodHosts, buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                builderTag, baseDisplayTag, backupDest)
}

def nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, gitScm, nonProdEnvs,
                  qaEnvs, releaseVersion, organizationName, appGitRepoName, gitCommit,
                  templates, secrets, secretsRemoteDests, startServerScripts, stopServerScripts,
                  remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts, appArtifactsRemoteDests,
                  nonProdHostsMap, prodHosts, buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                  builderTag, baseDisplayTag, backupDest) {

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
            [$class: 'ChoiceParameterDefinition', choices: nonProdEnvs , description: 'Environments', name: 'env']
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
      currentBuild.displayName = "${baseDisplayTag}-${deployEnv}"
      echo currentBuild.displayName

      baseDisplayTag = currentBuild.displayName

      nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber, gitScm, nonProdEnvs,
                         qaEnvs, releaseVersion, organizationName, appGitRepoName, gitCommit,
                         templates, secrets, secretsRemoteDests, startServerScripts, stopServerScripts,
                         remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts, appArtifactsRemoteDests,
                         nonProdHostsMap, prodHosts, buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                         builderTag, baseDisplayTag, releaseFlag, deployEnv, backupDest)
    }
  }
}

def nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber, gitScm, nonProdEnvs,
                       qaEnvs, releaseVersion, organizationName, appGitRepoName, gitCommit,
                       templates, secrets, secretsRemoteDests, startServerScripts, stopServerScripts,
                       remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts, appArtifactsRemoteDests,
                       nonProdHostsMap, prodHosts, buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                       builderTag, baseDisplayTag, releaseFlag, deployEnv, backupDest) {

  def commons = new com.aristotlecap.pipeline.Commons()
  def qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
  echo deployEnv
  echo qaEnvs
  echo "qaPassFlag::::"
  if (qaPassFlag) {
    echo "true!!!"
  } else {
    echo "false!!!"
  }

  def builderImage = "${env.TOOL_BUSYBOX}"

  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  }

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", command: 'cat', ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
    ]) {

    node(POD_LABEL) {

      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
      echo secretRoot

      def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'
      echo appRoleName

      def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
      echo workspace

      def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, false);
      echo "application vault auth token -> ${appVaultAuthToken}"

      def sshPrivateKeyRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/ssh-keys/id_rsa"

     container('vault') {
        //processing the private keys
        withEnv(["VAULT_TOKEN=${appVaultAuthToken}",
                 "SSH_PRIVATE_KEY_ROOT=${sshPrivateKeyRoot}"]) {
          sh """
            consul-template -vault-renew-token=false -once -template /home/jenkins/.ssh/id_rsa_vm.ctmpl:$workspace/id_rsa
            chmod 400 ${workspace}/id_rsa
            ls -la
            id -u
          """
        }
      }

      //load the environment map (environment specific scripts or settings)
      def envMap = commons.getEnironmentMap(deployEnv)

      //processing the application configuration files
      if (secrets != "null") {
        def secretRootBase = "secret/${organizationName}/${appGitRepoName}/nonprod"
        commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
      }

      echo workspace
      def hostMap = commons.getMapFromString(nonProdHostsMap)

      if (preInstallArtifacts!="null") {
        echo preInstallArtifacts
        echo preInstallArtifactsDests

        def hostsString = hostMap["${deployEnv}"]
        echo hostsString
        commons.deployPreInstallArtifacts(hostsString, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests)
      }

      def buildStepsEnv = commons.getScriptsByName(envMap, 'buildSteps')
      buildStepsEnv = (buildStepsEnv != 'null')? buildStepsEnv: buildSteps
      echo "do the Pre Deployment Execution"
      commons.localBuildSteps("Build & Test", buildStepsEnv)

      if (stopServerScripts != 'null') {
        stage ('Stop servers') {
          echo hostMap["${deployEnv}"]
          commons.parallelScriptRun(hostMap["${deployEnv}"], remoteAppUser, stopServerScripts, 'stop')
        }
      }

      def appArtifactsRemoteDestsEnv = commons.getScriptsByName(envMap, 'appArtifactsRemoteDests')
      appArtifactsRemoteDestsEnv = (appArtifactsRemoteDestsEnv != 'null')? appArtifactsRemoteDestsEnv: appArtifactsRemoteDests
      if (backupDest != 'null') {
        stage ('Backup Resources') {
          echo hostMap["${deployEnv}"]
          commons.backupResourceForRollback(hostMap["${deployEnv}"], remoteAppUser, appArtifactsRemoteDestsEnv, backupDest)
        }
      }

      def appArtifactsEnv = commons.getScriptsByName(envMap, 'appArtifacts')
      appArtifactsEnv = (appArtifactsEnv != 'null')? appArtifactsEnv: appArtifacts
      stage ("Deploy" ) {
        def hostsString = hostMap["${deployEnv}"]
        echo hostsString
        commons.deployArtifacts(hostsString, remoteAppUser, secrets, secretsRemoteDests, appArtifactsEnv, appArtifactsRemoteDestsEnv, deployEnv)
      }

      echo "Pre Start Server steps"
      def preStartServerLocalCmdsEnv = commons.getScriptsByName(envMap, 'preStartServerLocalCmds')
      preStartServerLocalCmdsEnv = (preStartServerLocalCmdsEnv != 'null')? preStartServerLocalCmdsEnv: preStartServerLocalCmds
      commons.localBuildSteps("Pre Start Sever Steps", preStartServerLocalCmdsEnv)

      def startServerScriptsEnv = commons.getScriptsByName(envMap, 'startServerScripts')
      startServerScriptsEnv = (startServerScriptsEnv != 'null')? startServerScriptsEnv: startServerScripts
      if (startServerScriptsEnv != 'null') {
        stage ('Start servers') {
          echo hostMap["${deployEnv}"]
          commons.parallelScriptRun(hostMap["${deployEnv}"], remoteAppUser, startServerScriptsEnv, 'start')
        }
      }

      echo "Post Start Server steps"
      commons.localBuildSteps("Post Start Server Steps", postStartServerLocalCmds)
    }
  }
  qaApproveOrRollback(projectTypeParam, gitBranchName, buildNumber,  builderTag, gitScm,
                      gitCommit, organizationName, appGitRepoName,
                      releaseVersion, templates, secrets, secretsRemoteDests, startServerScripts,
                      stopServerScripts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts,
                      appArtifactsRemoteDests, prodHosts,  buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                      baseDisplayTag, backupDest, qaPassFlag, nonProdHostsMap, deployEnv)
}

def qaApproveOrRollback(projectTypeParam, gitBranchName, buildNumber,  builderTag, gitScm,
                        gitCommit, organizationName, appGitRepoName,
                        releaseVersion, templates, secrets, secretsRemoteDests, startServerScripts,
                        stopServerScripts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts,
                        appArtifactsRemoteDests, prodHosts,  buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                        baseDisplayTag, backupDest, qaPassFlag, nonProdHostsMap, deployEnv) {

  stage("Rollback or QA Approve?") {
    checkpoint "QA Approve"
    currentBuild.displayName = baseDisplayTag

    def didTimeout = false
    def userInput
    def crNumber
    def rollbackFlag

    def parentDisplayName = currentBuild.rawBuild.getParent().getFullName()
    println "Parent = " + parentDisplayName
    if (backupDest != 'null' && qaPassFlag) {
      //possible rollback and QA approve
      try {
        timeout(time: 60, unit: 'SECONDS') {
          userInput = input(
            id: 'userInput', message: 'Approve Release?', parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Rollback the deployment?', name: 'rollbackFlag'],
              [$class: 'TextParameterDefinition', defaultValue: '', description: 'CR Number', name: 'crNumber']
          ])
          echo ("CR Number: "+userInput)
        }

        crNumber = userInput['crNumber']
        println crNumber

        rollbackFlag = userInput['rollbackFlag']
        println rollbackFlag

      } catch(err) { // timeout reached or input false
        didTimeout = true
      }
      if (didTimeout) {
        // do something on timeout
        echo "no input was received before timeout"
        currentBuild.result = 'SUCCESS'
      } else {
        if (rollbackFlag) {
          nonprodDeployRollbackLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                                     gitCommit, organizationName, appGitRepoName,
                                     releaseVersion, templates, secrets, secretsRemoteDests, startServerScripts,
                                     stopServerScripts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts,
                                     appArtifactsRemoteDests, prodHosts,  buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                                     baseDisplayTag, crNumber, backupDest, nonProdHostsMap, deployEnv)
        } else {
          qaApproveLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                         gitCommit, organizationName, appGitRepoName,
                         releaseVersion, templates, secrets, secretsRemoteDests, startServerScripts,
                         stopServerScripts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts,
                         appArtifactsRemoteDests, prodHosts,  buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                         baseDisplayTag, crNumber, backupDest, nonProdHostsMap)
        }
      }
    } else if (backupDest != 'null' && !qaPassFlag) {
      //possible rollback only
      try {
        timeout(time: 60, unit: 'SECONDS') {
          userInput = input(
            id: 'userInput', message: 'Approve Release?', parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Rollback the deployment?', name: 'rollbackFlag']
          ])
        }
        rollbackFlag = userInput
        println rollbackFlag
      } catch(err) { // timeout reached or input false
        didTimeout = true
      }
      if (didTimeout) {
        // do something on timeout
        echo "no input was received before timeout"
        currentBuild.result = 'SUCCESS'
      } else {
        if (rollbackFlag) {
          nonprodDeployRollbackLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                                     gitCommit, organizationName, appGitRepoName,
                                     releaseVersion, templates, secrets, secretsRemoteDests, startServerScripts,
                                     stopServerScripts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts,
                                     appArtifactsRemoteDests, prodHosts,  buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                                     baseDisplayTag, userInput, backupDest, nonProdHostsMap, deployEnv)
        }
      }
    } else if (backupDest == 'null' && qaPassFlag) {
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
        qaApproveLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                       gitCommit, organizationName, appGitRepoName,
                       releaseVersion, templates, secrets, secretsRemoteDests, startServerScripts,
                       stopServerScripts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts,
                       appArtifactsRemoteDests, prodHosts,  buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                       baseDisplayTag, userInput, backupDest, nonProdHostsMap)
      }
    }
  }
}

def nonprodDeployRollbackLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                               gitCommit, organizationName, appGitRepoName,
                               releaseVersion, templates, secrets, secretsRemoteDests, startServerScripts,
                               stopServerScripts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts,
                               appArtifactsRemoteDests, prodHosts,  buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                               baseDisplayTag, userInput, backupDest, nonProdHostsMap, deployEnv) {

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
  ]) {
    node(POD_LABEL) {
      // Clean workspace before doing anything
      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      def commons = new com.aristotlecap.pipeline.Commons()

      def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
      echo secretRoot

      def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'
      echo appRoleName

      def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
      echo workspace

      def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, false);
      echo "application vault auth token -> ${appVaultAuthToken}"

      def sshPrivateKeyRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/ssh-keys/id_rsa"

      container('vault') {
        //processing the private keys
        withEnv(["VAULT_TOKEN=${appVaultAuthToken}",
                 "SSH_PRIVATE_KEY_ROOT=${sshPrivateKeyRoot}"]) {
          sh """
            consul-template -vault-renew-token=false -once -template /home/jenkins/.ssh/id_rsa_vm.ctmpl:$workspace/id_rsa
            chmod 400 ${workspace}/id_rsa
            ls -la
            id -u
          """
        }
      }

      def hostMap = commons.getMapFromString(nonProdHostsMap)
      def hostsString = hostMap["${deployEnv}"]
      echo hostsString

      commons.restoreResourceForRollback(hostsString, remoteAppUser, appArtifactsRemoteDests, backupDest)

    }
  }
}

def qaApproveLogic(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                   gitCommit, organizationName, appGitRepoName,
                   releaseVersion, templates, secrets, secretsRemoteDests, startServerScripts,
                   stopServerScripts, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, appArtifacts,
                   appArtifactsRemoteDests, prodHosts,  buildSteps, preStartServerLocalCmds, postStartServerLocalCmds,
                   baseDisplayTag, userInput, backupDest, nonProdHostsMap) {

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
  ]) {
    node(POD_LABEL) {
      def commons = new com.aristotlecap.pipeline.Commons()

      //sh 'git config -l'
      //echo sh(script: 'env|sort', returnStdout: true)
      sh """
        git config --global user.email "jenkins@westernasset.com"
        git config --global user.name "Jenkins Agent"
        git config --global http.sslVerify false
        git config --global push.default matching
        git config -l
      """
      echo gitScm
      echo gitBranchName
      echo gitCommit

      // Clean workspace before doing anything
      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
      sh "git reset --hard ${gitCommit}"

      def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}-${userInput}"
      sh """
        git config --global user.email "jenkins@westernasset.com"
        git config --global user.name "Jenkins Agent"
        git config --global http.sslVerify false
        git config --global push.default matching
        git config -l

        ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${userInput}" '
        ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
      """

      stage("QA approve") {
        echo "Approle release for CR ${userInput}"
        echo "Trigger the production release task!!!"

        currentBuild.displayName = baseDisplayTag + '-' + userInput

        echo buildNumber
        echo gitBranchName
        echo gitCommit
        echo gitScm
        echo organizationName
        echo appGitRepoName

        build job: "${env.opsReleaseJob}", wait: false, parameters: [
            [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
            [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)],
            [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(userInput)],
            [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
            [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
            [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
            [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
            [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
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
            [$class: 'StringParameterValue', name: 'preStartServerLocalCmds', value: String.valueOf(preStartServerLocalCmds)],
            [$class: 'StringParameterValue', name: 'postStartServerLocalCmds', value: String.valueOf(postStartServerLocalCmds)],
            [$class: 'StringParameterValue', name: 'backupDest', value: String.valueOf(backupDest)],
            [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)]
        ]
      }
    }
  }
}
