#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  mavenBuild()
  dockerBuild()


}

def mavenBuild() {

  def label = "agent-${UUID.randomUUID().toString()}"
  podTemplate(label: label, cloud: 'pas-development', containers: [
      containerTemplate(name: 'maven', image: 'pasdtr.westernasset.com/devops/jenkins-builder:j8u151-m3.5.2-s6.5.1.240', ttyEnabled: true, command: 'cat')
    ],
    imagePullSecrets: [ 'regcred' ],
    volumes: [persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/root/.m2')]) {

      node(label) {
        stage('Get a Maven project') {
          // Clean workspace before doing anything
          deleteDir()
          checkout scm

          def gitCommit=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
          echo gitCommit

          String gitRemoteURL = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
          echo gitRemoteURL

          def gitScm = "git@github.westernasset.com:" + gitRemoteURL.drop(32)
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

            container('maven') {
                stage('Build a Maven project') {
                    sh '''
                      pwd
                      ls -la
                      mvn -B clean install
                    '''
                }
            }
        }
      }
  }

}



def dockerBuild() {

  //4d43f04b-7543-44d3-8b44-ab9e5c48de2a

  def label = "agent-${UUID.randomUUID().toString()}"
  podTemplate(label: label, cloud: 'pas-development', containers: [
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')]) {

    def image = "jenkins/jnlp-slave"
    node(label) {
      stage('Build Docker image') {
        git 'https://github.com/jenkinsci/docker-jnlp-slave.git'
        container('docker') {

          docker.withRegistry("https://artifactorydev.westernasset.com", "4d43f04b-7543-44d3-8b44-ab9e5c48de2a") {
            sh """
              docker build -t ${image} .
              docker tag jenkins/jnlp-slave artifactorydev.westernasset.com/docker-nonprod/${image}
              docker push artifactorydev.westernasset.com/docker-nonprod/${image}
              docker tag jenkins/jnlp-slave artifactorydev.westernasset.com/docker-prod/${image}
              docker push artifactorydev.westernasset.com/docker-prod/${image}

            """
          }
        }
      }
    }
  }

}
