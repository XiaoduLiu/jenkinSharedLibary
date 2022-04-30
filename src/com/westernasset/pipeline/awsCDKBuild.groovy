package com.westernasset.pipeline;

import com.westernasset.pipeline.steps.*

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonprodAccounts,
          prodAccounts, releaseVersion, accountAppfileMap, appfileStackMap,
          templates, secrets, publishAsNpmMoudule) {

  def commons = new com.westernasset.pipeline.Commons()

  def cdkBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag
  def repo

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'busy', image: "${env.TOOL_BUSYBOX}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'cdk', image: "${cdkBuilderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ]){
    node(POD_LABEL) {
      currentBuild.displayName  = "${gitBranchName}-${releaseVersion}-${buildNumber}"
      try {
        repo = commons.clone()
        container('cdk') {
          commons.setNpmrcFilelink()
        }
        commons.secretProcess(templates, secrets, 'cdk', repo.organizationName, repo.appGitRepoName, false)
        commons.copySecretsToLocationForCDK(templates, secrets)
        container('cdk') {
          stage("Synth") {
            commons.npmBuild()
            commons.awsCDKSynth(nonprodAccounts, accountAppfileMap, appfileStackMap)
          }
          stage("Diff") {
            commons.awsCDKDiff(nonprodAccounts, accountAppfileMap, appfileStackMap)
          }
        }
      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }

  Prompt prompt = new Prompt()
  if (!prompt.releaseNonProd()) {
      return // Do not proceed if there is no approval
  }

  deployNonprodResourceLogic(projectTypeParam, gitBranchName, buildNumber, repo.organizationName, repo.appGitRepoName,
                             repo.gitScm, repo.gitCommit, builderTag, nonprodAccounts, prodAccounts,
                             releaseVersion, accountAppfileMap, appfileStackMap,
                             templates, secrets, publishAsNpmMoudule)

}

def deployNonprodResourceLogic(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                               gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
                               releaseVersion, accountAppfileMap, appfileStackMap,
                               templates, secrets, publishAsNpmMoudule) {

  def commons = new com.westernasset.pipeline.Commons()

  def cdkBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'cdk', image: "${cdkBuilderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws'),
        persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
        persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
    ]) {
    node(POD_LABEL) {
      try {
        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/cdk"
        echo secretRoot

        def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'
        echo appRoleName

        def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, false);
        echo "application vault auth token -> ${appVaultAuthToken}"

        def secretRootBase = "secret/${organizationName}/${appGitRepoName}/nonprod"

        container('cdk') {
          commons.setNpmrcFilelink()
        }

        commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)

        commons.copySecretsToLocationForCDK(templates, secrets)

        stage('Deploy To Non-Prod') {
          container('cdk') {
            commons.npmBuild()
            commons.awsCDKSynth(nonprodAccounts, accountAppfileMap, appfileStackMap)
            commons.awsCDKDiff(nonprodAccounts, accountAppfileMap, appfileStackMap)
            commons.awsCDKDeploy(nonprodAccounts, organizationName, appGitRepoName, accountAppfileMap, appfileStackMap)
          }
          def nonprodAcc = nonprodAccounts.replaceAll("\n", ",")
          def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}-${buildNumber}-${nonprodAcc}"
          sh """
            git config --global user.email "jenkins@westernasset.com"
            git config --global user.name "Jenkins Agent"
            git config --global http.sslVerify false
            git config --global push.default matching
            git config -l

            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}"'
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
          """
        }
      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, true, false, currentBuild.displayName, 'Ready to Release?', 'Approve Release?')
  if (gate.destroyFlag) {
    destroyNonprodResource(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                           gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
                           releaseVersion, accountAppfileMap, appfileStackMap)
  } else if (gate.crNumber != null) {
    qaApprove(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
              gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
              releaseVersion, accountAppfileMap, appfileStackMap, gate.crNumber,
              templates, secrets)
  }
}

def destroyNonprodResource(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                           gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
                           releaseVersion, accountAppfileMap, appfileStackMap) {
  def commons = new com.westernasset.pipeline.Commons()
  def cdkBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-non-prod-stacks-Destory"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'cdk', image: "${cdkBuilderImage}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws'),
        persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ]) {
    node(POD_LABEL) {
      try {
        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"
        container('cdk') {
          commons.setNpmrcFilelink()
        }
        stage('Destory Non-Prod Stacks') {
          container('cdk') {
            commons.npmBuild()
            commons.awsCDKDestroy(nonprodAccounts, accountAppfileMap, appfileStackMap)
          }
        }
      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
}

def qaApprove(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
              gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
              releaseVersion, accountAppfileMap, appfileStackMap, crNumber,
              templates, secrets) {
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
  stage('trigger downstream job') {
    build job: "${env.opsReleaseJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
      [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
      [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(crNumber)],
      [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
      [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
      [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
      [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
      [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
      [$class: 'StringParameterValue', name: 'prodAccounts', value: String.valueOf(prodAccounts)],
      [$class: 'StringParameterValue', name: 'accountAppfileMap', value: String.valueOf(accountAppfileMap)],
      [$class: 'StringParameterValue', name: 'appfileStackMap', value: String.valueOf(appfileStackMap)],
      [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
      [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)],
      [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
      [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)]
    ]
  }
}
