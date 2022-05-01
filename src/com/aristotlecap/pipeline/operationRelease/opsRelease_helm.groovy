package com.aristotlecap.pipeline.operationRelease

import java.util.*
import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*
import com.aristotlecap.pipeline.*
import groovy.json.JsonSlurper

def build(params) {
  print params

  def didTimeout

  HelmModel config = HelmModel.fromJsonString(params.config)
  println "config -> " + config.toString()
  String buildNumber = params.buildNumber
  String crNumber = params.crNumber
  String gitBranchName = params.gitBranchName
  GitRepository repo = GitRepository.fromJsonString(params.repo)
  String releaseVersion = config.releaseVersion

  def imageTagMap = (params.imageTagMap == 'null')? null:getMap(params.imageTagMap)

  Prompt prompt = new Prompt()
  HelmBuild helmBuild = new HelmBuild()

  println config
  println buildNumber
  println crNumber
  println gitBranchName
  println repo.toJsonString()
  println releaseVersion

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

  if (!prompt.approve(message: 'Approve Release?')) {
      return // Do not proceed if there is no approval
  }

  stage("Production Deploy") {
    helmBuild.multiVendorDeploy(config, buildNumber, config.prodEnv, imageTagMap, gitBranchName, repo, true, crNumber)
  }

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
