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

  def commons = new com.westernasset.pipeline.Commons()
  def qaEnvsArray = commons.getQaEnv(config.qaEnvs)

  def qaEnvsString = "null"
  if (qaEnvsArray != null && qaEnvsArray.size() > 0) {
     qaEnvsString = qaEnvsArray.join("\n")
  }

  def templatesArray = config.templates
  def templatesString = "null"
  if (templatesArray != null && templatesArray.size() > 0) {
     templatesString = templatesArray.join("\n")
  }

  def secretsArray = config.secrets
  def secretsString = "null"
  if (secretsArray != null && secretsArray.size() > 0) {
     secretsString = secretsArray.join("\n")
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

  def build = new com.westernasset.pipeline.dockerServiceBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "dockerService",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${nonProdEnvString}",
        "${qaEnvsString}",
        "${config.prodEnv}",
        "${config.drEnv}",
        "${config.releaseVersion}",
        "${templatesString}",
        "${secretsString}",
        "${buildStepsString}",
        "${config.mixCaseRepo}",
        "${postDeployStepsString}",
        config.e2eEnv,
        config.e2eTestSteps
      )
    }
  } else {
    build.build(
      "dockerService",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${nonProdEnvString}",
      "${qaEnvsString}",
      "${config.prodEnv}",
      "${config.drEnv}",
      "${config.releaseVersion}",
      "${templatesString}",
      "${secretsString}",
      "${buildStepsString}",
      "${config.mixCaseRepo}",
      "${postDeployStepsString}",
      config.e2eEnv,
      config.e2eTestSteps
    )
  }
}
