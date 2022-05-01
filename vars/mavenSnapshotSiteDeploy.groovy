#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel
  if (lockLabel != 'null') {
    lock(label: "${env.lockLabel}")  {
      mavenSnapshotSiteDeployLogic(
        "${params.gitBranchName}",
        "${params.buildNumber}",
        "${params.builderTag}",
        "${params.gitScm}",
        "${params.gitCommit}")
    }
  } else {
    mavenSnapshotSiteDeployLogic(
      "${params.gitBranchName}",
      "${params.buildNumber}",
      "${params.builderTag}",
      "${params.gitScm}",
      "${params.gitCommit}")
  }
}

def mavenSnapshotSiteDeployLogic(gitBranchName, buildNumber,builderTag,
                                 gitScm, gitCommit) {

  def label = "mypod-${UUID.randomUUID().toString()}"
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  podTemplate(
    label: label,
    cloud: 'pas-development',
    containers: [
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
    ]) {
    node(label) {
      def commons = new com.aristotlecap.pipeline.Commons()
      try {
        stage ('Maven Snapshot Site-Deploy') {
          // Clean workspace before doing anything
          deleteDir()
          git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"

          echo "${params.gitBranchName}"
          echo "${params.gitCommit}"

          sh "git reset --hard ${gitCommit}"
          def pom = readMavenPom file: 'pom.xml'

          print pom
          print pom.version
          def pomversion = pom.version
          sh 'pwd'
          //sh 'who'
          //sh 'ls -la /home/jenkins'
          //sh 'ls -la /opt/resources'
          //sh 'set'
          echo sh(script: 'env|sort', returnStdout: true)

          def parentDisplayName = currentBuild.rawBuild.getParent().getFullName()
          println "Parent = " + parentDisplayName

          currentBuild.displayName = "${gitBranchName}-${pomversion}-${buildNumber}"

          commons.snapshotSiteDeploy(gitBranchName)
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
}
