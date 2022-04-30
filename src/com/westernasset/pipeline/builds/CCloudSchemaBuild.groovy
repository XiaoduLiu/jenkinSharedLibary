package com.westernasset.pipeline.builds

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*

def deploy(builderTag, paths, environment, buildNumber, branchName) {

  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def ccloud = new CCloud()
  def repo
  def yaml

  def builderImage = BuilderImage.fromTag(env, builderTag).getImage()
  print builderImage

  print paths
  print environment
  print buildNumber
  print branchName

  pod.node(
    containers: [
      ccloud.containerTemplate(builderImage)
    ],
    volumes: [
      ssh.keysVolume()
    ]
  ) {
    stage('Clone') {
      if (environment != 'prod') {
        repo = gitScm.checkout()
      } else {
        deleteDir()
        git url: repoScm, credentialsId: 'ghe-jenkins', branch: "${branchName}"
        sh "git reset --hard ${gitCommit}"
      }
    }
    stage('Non-Prod Deployment') {
      ccloud.ccloudLogin()
      ccloud.uploadSchema(paths, environment)
    }
  }
  return repo
}
