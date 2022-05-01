package com.aristotlecap.pipeline.operationRelease

import com.aristotlecap.pipeline.util.ConfluentUtil
import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*
import com.aristotlecap.pipeline.*
import java.util.*
import groovy.json.JsonSlurper

def build(params) {
  print params
  def didTimeout

  def kda = new KdaBuild()
  def helm = new HelmBuild()
  def batch = new BatchScriptBuild()
  Prompt prompt = new Prompt()

  KdaAppConfig config = KdaAppConfig.fromJsonString(params.config)
  String buildNumber = params.buildNumber
  String crNumber = params.crNumber
  String gitBranchName = params.gitBranchName
  GitRepository repo = GitRepository.fromJsonString(params.repo)
  String releaseVersion = params.releaseVersion
  def imageTagMap = getMap(params.imageTagMap)
  def archiveMap = getMap(params.archiveMap)
  String projectName = params.projectName
  String upstreamJobName = params.upstreamJobName
  String upstreamBuildNumber = params.upstreamBuildNumber

  println config
  println buildNumber
  println crNumber
  println gitBranchName
  println repo.toJsonString()
  println releaseVersion
  println imageTagMap
  println archiveMap
  println upstreamJobName
  println upstreamBuildNumber

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

  if (!prompt.approve(message: 'Approve Release?')) {
      return // Do not proceed if there is no approval
  }

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

  stage("Helm Deploy") {
    helm.prodDeploy(config, projectName, buildNumber, config.prodEnv, imageTagMap, repo)
  }
  stage("Batch Deploy") {
    batch.prodBatchBuild(config, config.prodEnv, imageTagMap, buildNumber, repo)
  }
  kda.prodDeploy(config, projectName, buildNumber, config.prodEnv, repo, upstreamJobName, upstreamBuildNumber, archiveMap)

}

@NonCPS
def getMap(jsonString) {
  JsonSlurper parser = new JsonSlurper()
  Map json = parser.parseText(jsonString)
  def map = [:]
  json.each { key, val ->
    map[key]=val
  }
  return map
}
