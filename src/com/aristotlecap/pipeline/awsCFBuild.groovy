package com.aristotlecap.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonprodAccounts,
          prodAccounts, releaseVersion, accountAppfileMap, appfileStackMap,
          parametersOverridesMap) {
  def repo
  def commons = new com.aristotlecap.pipeline.Commons()
  def awsBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  echo builderTag
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: env.TOOL_AGENT, args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'aws', image: awsBuilderImage, ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
  ])  {
    node(POD_LABEL) {
      currentBuild.displayName = "${gitBranchName}-${buildNumber}"
      try {
        repo = commons.clone()
        stage("Dry Run") {
          container('aws') {
            commons.awsCFDryrun(nonprodAccounts, accountAppfileMap, appfileStackMap,
                                parametersOverridesMap, repo.organizationName,
                                repo.appGitRepoName)
          }
        }
        //echo sh(script: 'env|sort', returnStdout: true)
        currentBuild.displayName  = "${gitBranchName}-${releaseVersion}-${buildNumber}"
      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonprodAccounts, false, false, false, currentBuild.displayName)
  if (!gate.abortedOrTimeoutFlag) {
    deployNonprodResource(projectTypeParam, gitBranchName, buildNumber, repo.organizationName, repo.appGitRepoName,
                          repo.gitScm, repo.gitCommit, builderTag, nonprodAccounts, prodAccounts,
                          releaseVersion, accountAppfileMap, appfileStackMap, gate.deployEnv,
                          parametersOverridesMap)
  }
}

def deployNonprodResource(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                          gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
                          releaseVersion, accountAppfileMap, appfileStackMap, deployEnv,
                          parametersOverridesMap) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def awsBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'aws', image: "${awsBuilderImage}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
    ]) {
    node(POD_LABEL) {
      try {
        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"
        stage('Deploy To Non-Prod') {
          container('aws') {
            commons.awsCFDeploy(accountAppfileMap, appfileStackMap, parametersOverridesMap, organizationName, appGitRepoName, deployEnv)
          }
        }
      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, true, false, currentBuild.displayName, 'Ready to Release?', 'Approve Release?')
  if (gate.destroyFlag) {
    destroyNonprodResource(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                                gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
                                releaseVersion, accountAppfileMap, appfileStackMap, deployEnv,
                                parametersOverridesMap)
  } else if (gate.crNumber != null) {
    qaApprove(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
              gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
              releaseVersion, accountAppfileMap, appfileStackMap, gate.crNumber,
              parametersOverridesMap)
  }
}

def destroyNonprodResource(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                                gitScm, gitCommit, builderTag, nonprodAccounts, prodAccounts,
                                releaseVersion, accountAppfileMap, appfileStackMap, deployEnv,
                                parametersOverridesMap) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def awsBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-non-prod-stacks-Destory"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'aws', image: "${awsBuilderImage}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
  ]) {
    node(POD_LABEL) {
      try {
        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"
        stage('Destory Non-Prod Stacks') {
          container('aws') {
            commons.awsCFDestroy(deployEnv, accountAppfileMap, appfileStackMap)
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
              parametersOverridesMap) {
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
      [$class: 'StringParameterValue', name: 'parametersOverridesMap', value: String.valueOf(parametersOverridesMap)]
    ]
  }
}
