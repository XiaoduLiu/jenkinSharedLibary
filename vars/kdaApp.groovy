#!/usr/bin/groovy

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.builds.*
import groovy.json.JsonOutput

def validate(def config) {
  def errors = []
  if (!config.builderTag) {
    errors.add('Missing required field: mavenLib.builderTag')
  } else if (!(config.builderTag instanceof String)) {
    errors.add('Invalid type for field: mavenLib.builderTag (need to be String)')
  }
  if (config.downstreamProjects && !(config.downstreamProjects instanceof List)) {
    errors.add('Invalid type for field: mavenLib.downstreamProjects (need to be List of String)')
  }
  if (errors.size() > 0) {
    error errors.join('\n')
  }
}

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  print config

  print "From IM lib ..."

  validate(config)

  def conditionals = new Conditionals()
  def mavenBuild = new MavenBuild()
  def dockerBuild = new DockerBuild()
  def prompt = new Prompt()
  def kda = new KdaBuild()
  def helm = new HelmBuild()
  def batch = new BatchScriptBuild()
  def repo
  def appVersoin
  def dockerFileMap = [:]
  def imageTagMap = [:]
  def archiveMap = [:]
  def displayLabel

  String jsonString = getJson(config)
  print jsonString

  KdaAppConfig kConfig = KdaAppConfig.fromJsonString(jsonString)

  println "kConfig -> " + kConfig.toString()

  def buildImage = BuilderImage.fromTag(env, kConfig.builderTag)

  conditionals.lockWithLabel {
    (repo, appVersion, imageTagMap, archiveMap) = mavenBuild.snapshotBuildForDocker(kConfig, buildImage)
  }

  displayLabel = "${repo.branch}-${appVersion}-${env.BUILD_NUMBER}"

  def projectName = currentBuild.projectName
  //println 'projectName->>' + projectName
  def buildNumber = env.BUILD_NUMBER
  //println 'buildNumber-->>' + buildNumber

  def appSelectionMap = kConfig.getAppDeploySelectionMap()

  def input = prompt.nonProdWithDeployOption(kConfig.nonProdEnvs, kConfig.qaEnvs, appSelectionMap);
  if (input == null || input.isEmpty()) {
    return // Stop if user aborts or timeout
  }

  println input

  kConfig.applyAppDeploySelection(input)

  println kConfig

  def environment = input['environment']
  def deployEnv = environment.split(':')[0]
  currentBuild.displayName = displayLabel + "-" + deployEnv

  stage("Helm Deploy") {
    helm.nonprodDeploy(kConfig, projectName, buildNumber, environment, imageTagMap, repo.branch)
  }
  stage("Batch Deploy") {
    batch.nonprodBatchBuild(kConfig, environment, imageTagMap, buildNumber, repo.branch)
  }
  kda.nonprodDeploy(kConfig, projectName, buildNumber, environment, repo.branch, archiveMap)


  boolean isReleased = prompt.release()
  if (!isReleased) {
    return // Stop if user aborts or timeout
  }

  //maven release
  conditionals.lockWithLabel {
    (repo, appVersion, imageTagMap, archiveMap) = mavenBuild.releaseBuildForDocker(kConfig, buildImage)
  }

  String changeRequest = prompt.changeRequest()

  if (!changeRequest || changeRequest.isEmpty()) {
      return // Stop if timeout or change request not set
  }

  print imageTagMap

  //qa approve process
  mavenBuild.pushImages(kConfig, appVersion, changeRequest, buildNumber, imageTagMap, repo.branch)

  stage("Trigger Downstream Job") {
    build job: "${env.opsReleaseJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'projectType', value: "kdaApp"],
      [$class: 'StringParameterValue', name: 'config', value: jsonString],
      [$class: 'StringParameterValue', name: 'buildNumber', value: env.BUILD_NUMBER],
      [$class: 'StringParameterValue', name: 'crNumber', value: changeRequest],
      [$class: 'StringParameterValue', name: 'gitBranchName', value: env.BRANCH_NAME],
      [$class: 'StringParameterValue', name: 'repo', value: repo.toJsonString()],
      [$class: 'StringParameterValue', name: 'releaseVersion', value: appVersion],
      [$class: 'StringParameterValue', name: 'projectName', value: projectName],
      [$class: 'StringParameterValue', name: 'upstreamJobName', value: String.valueOf(env.JOB_NAME)],
      [$class: 'StringParameterValue', name: 'upstreamBuildNumber', value: String.valueOf(env.BUILD_NUMBER)],
      [$class: 'StringParameterValue', name: 'imageTagMap', value: JsonOutput.toJson(imageTagMap)],
      [$class: 'StringParameterValue', name: 'archiveMap', value: JsonOutput.toJson(archiveMap)]
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
def getKdaMap(m) {
  Map map = [:]
  m.each { key, val ->
    def v = val
    if (key == 'dependencies')  {
      v = getArray(val)
    }
    map[key]=v
  }
  return map
}

@NonCPS
def getKubeMap(m) {
  Map map = [:]
  m.each { key, val ->
    def v = val
    if (key == 'secrets')  {
      v = getMap(val)
    }
    map[key]=v
  }
  return map
}

@NonCPS
def getJson(config) {
  Map<String, Object> map = [:]
  map.builderTag = config.builderTag
  map.nonProdEnvs = getArray(config.nonProdEnvs)
  map.qaEnvs = getArray(config.qaEnvs)
  map.dependencies = getArray(config.dependencies)
  map.secretsTemplate = config.secretsTemplate
  map.prodEnv = config.prodEnv
  map.drEnv = config.drEnv
  List<Map<String, Object>> arr = new ArrayList<>()
  for (m in config.kdaApps) {
    def mm = getKdaMap(m)
    arr.add(mm)
  }
  map.kdaApps = arr
  List<Map<String, Object>> arr2 = new ArrayList<>()
  for (n in config.kubeApps) {
    def nn = getKubeMap(n)
    arr2.add(nn)
  }
  map.kubeApps = arr2
  return JsonOutput.toJson(map)
}
