package com.westernasset.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber,
                       builderTag, nonProdEnvs, liquibaseChangeLog, liquibaseBuilderTag,
                       qaEnvs, prodEnv, drEnv, releaseVersion, templates,
                       secrets, startServerScript, stopServerScript) {
  node('agent') {
    def gitCommit
    def pomversion

    def projectType = "${projectTypeParam}"
    currentBuild.displayName = "${gitBranchName}-${buildNumber}"

    def organizationName
    def appGitRepoName
    def appDtrRepo
    def gitScm

    def commons = new com.westernasset.pipeline.Commons()

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
      commons.mavenSnapshotBuild("${pasDtrUri}", "${pasBuilder}", "${builderTag}")

      build job: "${env.siteDeployJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
        [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
        [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
        [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)]
      ]

      nonprodDeploy(projectTypeParam, gitBranchName, buildNumber,
                    builderTag, gitScm, nonProdEnvs, liquibaseChangeLog, liquibaseBuilderTag,
                    qaEnvs, prodEnv, drEnv, releaseVersion, organizationName,
                    appGitRepoName, appDtrRepo, gitCommit, templates, secrets)
    } catch (err) {
      currentBuild.result = 'FAILED'
      throw err
    }
  }
}

def nonprodDeploy(projectTypeParam, gitBranchName, buildNumber,
                  builderTag, gitScm, nonProdEnvs, liquibaseChangeLog, liquibaseBuilderTag,
                  qaEnvs, prodEnv, drEnv, releaseVersion, organizationName,
                  appGitRepoName, appDtrRepo, gitCommit, templates, secrets) {
  stage("Should I deploy to Non-Prod?") {
    checkpoint "Deploy To Non-Prod"

    def didAbort = false
    def didTimeout = false

    def userInput
    def deployEnv
    def releaseFlag

    currentBuild.displayName = "${gitBranchName}-${buildNumber}"

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
      currentBuild.displayName = "${gitBranchName}-${buildNumber}-${deployEnv}"
      echo currentBuild.displayName

      nonProdDeployLogic(
        "${projectTypeParam}",
        "${gitScm}",
        "${gitBranchName}",
        "${gitCommit}",
        "${buildNumber}",
        "${deployEnv}",
        "${organizationName}",
        "${appGitRepoName}",
        "${liquibaseChangeLog}",
        "${builderTag}",
        "${env.liquibaseProjectFolder}",
        "${liquibaseBuilderTag}",
        "${templates}",
        "${secrets}",
        releaseFlag,
        nonProdEnvs,
        qaEnvs,
        prodEnv,
        drEnv,
        releaseVersion,
        appDtrRepo
      )
    }
  }
}

def nonProdDeployLogic(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                       deployEnv, organizationName, appGitRepoName, liquibaseChangeLog,
                       builderTag, liquibaseProjectFolder, liquibaseBuilderTag, templates,
                       secrets, releaseFlag, nonProdEnvs, qaEnvs,
                       prodEnv, drEnv, releaseVersion, appDtrRepo) {
  def commons = new com.westernasset.pipeline.Commons()
  def qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
  echo "qaPassFlag::::"
  if (qaPassFlag) {
    echo "true!!!"
  } else {
    echo "false!!!"
  }

  def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
  def secretRootBase = "secret/${organizationName}/${appGitRepoName}/nonprod"
  echo secretRoot

  def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'
  echo appRoleName

  def wp = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
  echo wp

  def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, false);
  echo "application vault auth token -> ${appVaultAuthToken}"

  //processing the private keys
  def pkey = "/home/jenkins/.ssh/id_rsa_vm.ctmpl"
  def p = "id_rsa"
  commons.templateProcessing(pkey, p, secretRoot, secretRootBase, appVaultAuthToken)

  sh """
    chmod 400 ${workspace}/id_rsa
  """

  commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
  echo workspace

  stage ('Run Ansible') {
    docker.withRegistry("https://${env.PAS_DTR_URI}") {
      docker.image("${env.IMAGE_BUIDER_REPO}:ansible-2.6.3").inside("--network=jenkins") {
        echo "$USER"
        sh """
          ls -la
          cat $wp/id_rsa
        """
        def lsla = sh(returnStdout: true, script: "ssh -i ${workspace}/id_rsa compserv@compdev1.westernasset.com 'ls -la'").trim()
        echo "lsla = ${lsla}"
        sh """
          ls -la
          ls -la target
          pwd
          ls -la ansible
          pwd
          ls -la $wp/ansible
          pwd
          chmod 400 $wp/id_rsa
          ls -la
          cat $wp/id_rsa
          cat $wp/ansible/hosts
          echo wp

          ansible --version
          ansible all -m ping -i $wp/ansible/hosts --private-key=$workspace/id_rsa
          ansible-playbook --version
          ansible-playbook -i $wp/ansible/hosts --private-key=$workspace/id_rsa --extra-vars \"workspace=$wp\" $wp/ansible/playbook.yml
        """
      }
    }
  }

  stage('Trigger Downstream Job') {
    //echo buildNumber
    if (qaPassFlag) {
      build job: "${env.buildReleaseJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
        [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
        [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
        [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
        [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
        [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
        [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
        [$class: 'StringParameterValue', name: 'prodEnv', value: String.valueOf(prodEnv)],
        [$class: 'StringParameterValue', name: 'drEnv', value: String.valueOf(drEnv)],
        [$class: 'StringParameterValue', name: 'userReleaseVersion', value: String.valueOf(releaseVersion)],
        [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
        [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: String.valueOf(liquibaseChangeLog)],
        [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: String.valueOf(liquibaseBuilderTag)],
        [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
        [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
        [$class: 'StringParameterValue', name: 'nonProdEnvs', value: String.valueOf(nonProdEnvs)]
      ]
    }
  }
}
