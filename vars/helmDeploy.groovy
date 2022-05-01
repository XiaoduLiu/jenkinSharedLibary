#!/usr/bin/groovy

import java.lang.String
import com.aristotlecap.pipeline.models.*

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

  def commons = new com.aristotlecap.pipeline.Commons()
  def qaEnvsArray = commons.getQaEnv(config.qaEnvs)

  def qaEnvsString = "null"
  if (qaEnvsArray != null && qaEnvsArray.size() > 0) {
     qaEnvsString = qaEnvsArray.join("\n")
  }

  def buildStepsArray = config.buildSteps
  def buildStepsString = "null"
  if (buildStepsArray != null && buildStepsArray.size() > 0) {
    buildStepsString = buildStepsArray.join("\n")
  }

  def postDeployStepsArray = config.postDeploySteps
  def postDeployStepsString = "null"
  if (postDeployStepsArray != null && postDeployStepsArray.size() > 0) {
    postDeployStepsString = postDeployStepsArray.join("\n")
  }

  def build = new com.aristotlecap.pipeline.helmDeployBuild()

  Validation validation = new Validation()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "helmDeploy",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${nonProdEnvString}",
        "${qaEnvsString}",
        "${config.prodEnv}",
        "${config.drEnv}",
        "${config.releaseVersion}",
        config.secrets,
        "${buildStepsString}",
        "${config.mixCaseRepo}",
        "${postDeployStepsString}",
        config.e2eEnv,
        config.e2eTestSteps,
        config.helmChartVersion
      )
    }
  } else {
    build.build(
      "helmDeploy",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${nonProdEnvString}",
      "${qaEnvsString}",
      "${config.prodEnv}",
      "${config.drEnv}",
      "${config.releaseVersion}",
      config.secrets,
      "${buildStepsString}",
      "${config.mixCaseRepo}",
      "${postDeployStepsString}",
      config.e2eEnv,
      config.e2eTestSteps,
      config.helmChartVersion
    )
  }
}
