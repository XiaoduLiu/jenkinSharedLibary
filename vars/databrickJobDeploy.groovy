#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def build = new com.aristotlecap.pipeline.databrickJobDeployBuild()

  def commons = new com.aristotlecap.pipeline.Commons()

  def jobIdsNonprodString = 'null'
  def jobIdsNonProdMap = config.jobIdsNonprod
  if (jobIdsNonProdMap.size()>0) {
    jobIdsNonprodString = commons.getStringFromMap(jobIdsNonProdMap)
  }
  print 'nonprod jobs '
  print jobIdsNonprodString

  def jobIdsProdString = 'null'
  def jobIdsProdMap = config.jobIdsProd
  if (jobIdsProdMap.size()>0) {
    jobIdsProdString = commons.getStringFromMap(jobIdsProdMap)
  }
  print 'prod jobs '
  print jobIdsProdString

  def deleteNonprodJobIdsArray = config.deleteNonprodJobIds
  def deleteNonprodJobIdsString = "null"
  if (deleteNonprodJobIdsArray != null && !deleteNonprodJobIdsArray.isEmpty()) {
     deleteNonprodJobIdsString = deleteNonprodJobIdsArray.join("\n")
  }

  def deleteProdJobIdsArray = config.deleteProdJobIds
  def deleteProdJobIdsString = "null"
  if (deleteProdJobIdsArray != null && !deleteProdJobIdsArray.isEmpty()) {
     deleteProdJobIdsString = deleteProdJobIdsArray.join("\n")
  }

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        'databrickJobDeploy',
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${jobIdsNonprodString}",
        "${jobIdsProdString}",
        "${deleteNonprodJobIdsString}",
        "${deleteProdJobIdsString}",
        "${config.releaseVersion}")
    }
  } else {
    build.build(
      'databrickJobDeploy',
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${jobIdsNonprodString}",
      "${jobIdsProdString}",
      "${deleteNonprodJobIdsString}",
      "${deleteProdJobIdsString}",
      "${config.releaseVersion}")
  }
}
