package com.aristotlecap.pipeline;

def build(projectType, gitBranchName, buildNumber, builderTag, appPath, module, fromEnv, toEnv, releaseVersion) {

  String gitCommit
  def repo
  def databrickImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'databrick', image: "${databrickImage}", ttyEnabled: true)
    ],
    volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
  ]) {
    node(POD_LABEL) {
      try {
        def commons = new com.aristotlecap.pipeline.Commons()
        repo = commons.clone()
        currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
        gitCommit = checkIntoGit(repo.gitScm, gitBranchName, repo.gitCommit, appPath, fromEnv, module, repo.appGitRepoName, releaseVersion)
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  promoteToProduction(projectType, repo.appGitRepoName, gitBranchName, buildNumber, builderTag, appPath, module, fromEnv, toEnv, releaseVersion, gitCommit, repo.gitScm)
}

def checkIntoGit(gitScm, gitBranchName, gitCommit, appPath, fromEnv, module, appGitRepoName, releaseVersion) {
  def gitCommitHash
  container('databrick') {
    deleteDir()
    git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
    sh "git reset --hard ${gitCommit}"
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.DATABRICK_JENKINS}",
      usernameVariable: 'DATABRICKS_HOST', passwordVariable: 'DATABRICKS_TOKEN']]) {
      sh """
        rm -rf .$appPath/$fromEnv/$module
        mkdir -p .$appPath/$fromEnv/$module
        databricks workspace export_dir $appPath/$fromEnv/$module .$appPath/$fromEnv/$module
      """
    }
    def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}"
    sh """
      git config --global user.email "jenkins@westernasset.com"
      git config --global user.name "Jenkins Agent"
      git config --global http.sslVerify false
      git config --global push.default matching
      git config -l
    """
    try {
      sh """
        git add .
        ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git commit -m "update ${appPath}/${fromEnv}/${module}" '
        ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push'
      """
    } catch(err) {
      print err
    }
    gitCommitHash=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
    echo gitCommitHash
  }
  return gitCommitHash
}

def promoteToProduction(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag, appPath, module, fromEnv, toEnv, releaseVersion, gitCommit, gitScm) {
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
  def gate = gateutil.gate(null, false, true, false, currentBuild.displayName, 'Ready to Release?', 'Approve Release?')
  if (gate.crNumber != null) {
    podTemplate(
      cloud: 'pas-development',
      serviceAccount: 'jenkins',
      namespace: 'devops-jenkins',
      containers: [
        containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}')
      ],
      volumes: [
          persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
    ]) {
      node(POD_LABEL) {
        try {
          currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${gate.crNumber}"
          def displayTag = currentBuild.displayName

          // Clean workspace before doing anything
          deleteDir()
          git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
          sh "git reset --hard ${gitCommit}"

          def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}"
          sh """
            git config --global user.email "jenkins@westernasset.com"
            git config --global user.name "Jenkins Agent"
            git config --global http.sslVerify false
            git config --global push.default matching
            git config -l
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gate.crNumber}" '
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
          """
          stage('Trigger Downstream Job') {
            build job: "${env.opsReleaseJob}", wait: false, parameters: [
              [$class: 'StringParameterValue', name: 'projectType', value: 'databricksWorkbookCopy'],
              [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
              [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
              [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
              [$class: 'StringParameterValue', name: 'appPath', value: String.valueOf(appPath)],
              [$class: 'StringParameterValue', name: 'module', value: String.valueOf(module)],
              [$class: 'StringParameterValue', name: 'fromEnv', value: String.valueOf(fromEnv)],
              [$class: 'StringParameterValue', name: 'toEnv', value: String.valueOf(toEnv)],
              [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(displayTag)],
              [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
              [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
              [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(gate.crNumber)],
              [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)]
            ]
          }
        } catch (err) {
          currentBuild.result = 'FAILED'
          throw err
        }
      }
    }
  }
}
