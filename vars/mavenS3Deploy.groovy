#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def nonProdEnvArray = config.nonProdEnvs
  def nonProdEnvString = "null"
  if (nonProdEnvArray != null && nonProdEnvArray.size() > 0) {
     nonProdEnvString = nonProdEnvArray.join("\n")
  }

  def qaEnvsArray = config.qaEnvs
  def qaEnvsString = "null"
  if (qaEnvsArray != null && qaEnvsArray.size() > 0) {
     qaEnvsString = qaEnvsArray.join("\n")
  }

  //What to deploy
  def appArtifactsArray = config.appArtifacts
  def appArtifactsString = "null"
  if (appArtifactsArray != null && appArtifactsArray.size() > 0) {
     appArtifactsString = appArtifactsArray.join("\n")
  }

  def commons = new com.aristotlecap.pipeline.Commons()
  def S3KeyMapString = commons.getStringFromMap(config.S3KeyMap)

  def build = new com.aristotlecap.pipeline.mavenS3DeployBuild()
  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "mavenS3Deploy",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${nonProdEnvString}",
        "${qaEnvsString}",
        appArtifactsString,
        "${config.nonprodBucket}",
        "${config.prodBucket}",
        S3KeyMapString
      )
    }
  } else {
    build.build(
      "mavenS3Deploy",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${nonProdEnvString}",
      "${qaEnvsString}",
      appArtifactsString,
      "${config.nonprodBucket}",
      "${config.prodBucket}",
      S3KeyMapString
    )
  }
}
