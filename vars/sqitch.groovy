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

  def continuousDeploymentEnvsArray = config.continuousDeploymentEnvs
  def continuousDeploymentEnvsString = "null"
  if (continuousDeploymentEnvsArray != null && continuousDeploymentEnvsArray.size() > 0) {
     continuousDeploymentEnvsString = continuousDeploymentEnvsArray.join("\n")
  }

  def build = new com.aristotlecap.pipeline.sqitchBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "sqitch",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${nonProdEnvString}",
        "${qaEnvsString}",
        "${continuousDeploymentEnvsString}",
        "${config.releaseVersion}",
        "${config.verifyFromChange}"
      )
    }
  } else {
    build.build(
      "sqitch",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${nonProdEnvString}",
      "${qaEnvsString}",
      "${continuousDeploymentEnvsString}",
      "${config.releaseVersion}",
      "${config.verifyFromChange}"
    )
  }
}
