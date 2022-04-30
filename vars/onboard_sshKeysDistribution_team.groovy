#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def org = params.organizationName
  def repo = params.repoName
  def serverList = params.serverList
  def appUser = params.appUser
  def serverEnv = params.env

  currentBuild.displayName = "${org}-${repo}-${serverList}-${appUser}"

  stage("Sending the Setup Request") {
    build job: "${env.sshKeyDistributionJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(org)],
      [$class: 'StringParameterValue', name: 'repoName', value: String.valueOf(repo)],
      [$class: 'StringParameterValue', name: 'serverList', value: String.valueOf(serverList)],
      [$class: 'StringParameterValue', name: 'appUser', value: String.valueOf(appUser)],
      [$class: 'StringParameterValue', name: 'env', value: String.valueOf(serverEnv)]
    ]
  }

}
