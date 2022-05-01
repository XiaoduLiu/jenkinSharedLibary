package com.aristotlecap.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, libraryType, angularLibs) {
  def repo
  def releaseVersion
  def commons = new com.aristotlecap.pipeline.Commons()
  def npm = new com.aristotlecap.pipeline.util.npmUtil()

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'npm', image: "${builderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ]){
    node(POD_LABEL) {
      try {
        currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
        repo = commons.clone()
        stage('Get Release Version') {
          releaseVersion = sh(returnStdout: true, script: "cat package.json | jq '.version'").trim().replace("\"", "")
          currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
        }
        stage('Build') {
          container('npm') {
            npm.setNpmrcFilelink()
            npm.npmBuild(libraryType, angularLibs)
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
  def gate = gateutil.gate(null, false, false, false, currentBuild.displayName, 'Release to Production?', 'Approve Release?')
  if (!gate.abortedOrTimeoutFlag) {
    release(projectTypeParam, gitBranchName, buildNumber, repo.organizationName, repo.appGitRepoName,
            repo.gitScm, repo.gitCommit, builderTag, releaseVersion, libraryType, angularLibs)
  }
}

def release(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                 gitScm, gitCommit, builderTag, releaseVersion, libraryType, angularLibs) {

  def commons = new com.aristotlecap.pipeline.Commons()
  def npm = new com.aristotlecap.pipeline.util.npmUtil()

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'npm', image: "${builderImage}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm'),
        persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
    ]) {
    node(POD_LABEL) {
      try {

        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        stage('Release') {
          def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}"
          sh """
            git config --global user.email "jenkins@westernasset.com"
            git config --global user.name "Jenkins Agent"
            git config --global http.sslVerify false
            git config --global push.default matching
            git config -l

            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}" '
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
          """
          container('npm') {
            npm.setNpmrcFilelink()
            npm.npmBuild(libraryType, angularLibs)
            npm.npmRelease(libraryType, angularLibs)
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
