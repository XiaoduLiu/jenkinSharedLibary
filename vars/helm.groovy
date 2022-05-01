#!/usr/bin/groovy

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*
import groovy.json.JsonOutput

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  print config

  def prompt = new Prompt()
  def helmBuild = new HelmBuild()
  def dockerBuild = new DockerBuild()
  def conditionals = new Conditionals()
  def mavenBuild = new MavenBuild()
  def repo
  def tagMap
  def appVersion
  def imageTagMap
  def appVersoin
  def dockerFileMap = [:]
  def displayLabel

  currentBuild.displayName = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}"

  String jsonString = getJson(config)
  print jsonString

  HelmModel helmModel = HelmModel.fromJsonString(jsonString)

  println "helmModel -> " + helmModel.toString()

  if (null != helmModel.builderTag) {
      def buildImage = BuilderImage.fromTag(env, helmModel.builderTag)
      (repo, appVersion, tagMap) = mavenBuild.mavenAndNonprodMultiBuilds(helmModel, buildImage)
      helmModel.releaseVersion = appVersion
  }  else if (null != helmModel.dockerfiles && !helmModel.dockerfiles.isEmpty()) {
      (repo, tagMap) = dockerBuild.nonprodMultiBuilds(helmModel)
  }

  print helmModel

  def environment = prompt.nonprod(helmModel.nonProdEnvs, helmModel.nonProdEnvs);
  if (environment == null || environment.isEmpty()) {
    return // Stop if user aborts or timeout
  }

  stage("Nonprod Deployment") {
    repo = helmBuild.multiVendorDeploy(helmModel, env.BUILD_NUMBER, environment, tagMap, env.BRANCH_NAME, repo, false, null)
  }

  if (null != helmModel.builderTag) {
    boolean isReleased = prompt.release()
    if (!isReleased) {
      return // Stop if user aborts or timeout
    }

    def buildImage = BuilderImage.fromTag(env, helmModel.builderTag)
    (repo, appVersion, tagMap) = mavenBuild.mavenReleaseMultiBuilds(helmModel, buildImage)
    helmModel.releaseVersion = appVersion
  }

  String changeRequest = prompt.changeRequest()

  if (!changeRequest || changeRequest.isEmpty()) {
    return // Stop if timeout or change request not set
  }

  def deployEnv = environment.split(':')[0]
  currentBuild.displayName = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}-${deployEnv}-${changeRequest}"

  if (null != helmModel.dockerfiles && !helmModel.dockerfiles.isEmpty()) {
    imageTagMap = dockerBuild.pushMultiProdImages(repo, tagMap, helmModel.releaseVersion, changeRequest)
  }

  def imageTagMapString = (imageTagMap!=null)?JsonOutput.toJson(imageTagMap):'null'

  stage("Trigger Downstream Job") {
    build job: "${env.opsReleaseJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'projectType', value: "helm"],
      [$class: 'StringParameterValue', name: 'config', value: jsonString],
      [$class: 'StringParameterValue', name: 'buildNumber', value: env.BUILD_NUMBER],
      [$class: 'StringParameterValue', name: 'crNumber', value: changeRequest],
      [$class: 'StringParameterValue', name: 'gitBranchName', value: env.BRANCH_NAME],
      [$class: 'StringParameterValue', name: 'repo', value: repo.toJsonString()],
      [$class: 'StringParameterValue', name: 'imageTagMap', value: JsonOutput.toJson(imageTagMap)]
    ]
  }

}

@NonCPS
def getArray(list) {
  List<String> arr = new ArrayList<>()
  for(item in list) {
    arr.add(item)
  }
  return arr
}

@NonCPS
def getMap(m) {
  Map map = [:]
  m.each { key, val ->
    map[key]=val
  }
  return map
}

@NonCPS
def getJson(config) {
  Map<String, Object> map = [:]
  map.builderTag = config.builderTag
  map.nonProdEnvs = getArray(config.nonProdEnvs)
  map.qaEnvs = getArray(config.qaEnvs)
  map.prodEnv = config.prodEnv
  map.drEnv = config.drEnv
  List<Map<String, Object>> arr = new ArrayList<>()
  for (m in config.charts) {
    def mm = getMap(m)
    arr.add(mm)
  }
  map.charts = arr
  map.releaseVersion = config.releaseVersion
  map.helmRepos = getMap(config.helmRepos)
  map.dockerfiles = getMap(config.dockerfiles)
  map.secrets = getMap(config.secrets)
  map.kuberneteSecrets = getMap(config.kuberneteSecrets)
  return JsonOutput.toJson(map)
}
