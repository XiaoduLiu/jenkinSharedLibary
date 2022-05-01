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

  def buildTimeTemplatesArray = config.buildTimeTemplates
  def buildTimeTemplatesString = "null"
  if (buildTimeTemplatesArray != null && buildTimeTemplatesArray.size() > 0) {
     buildTimeTemplatesString = buildTimeTemplatesArray.join("\n")
  }

  def buildTimeSecretsArray = config.buildTimeSecrets
  def buildTimeSecretsString = "null"
  if (buildTimeSecretsArray != null && buildTimeSecretsArray.size() > 0) {
     buildTimeSecretsString = buildTimeSecretsArray.join("\n")
  }

  def build = new com.aristotlecap.pipeline.dockerBatchBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "dockerBatch",
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
        "${buildTimeTemplatesString}",
        "${buildTimeSecretsString}",
        "${config.mixCaseRepo}",
        "${config.testEnv}"
      )
    }
  } else {
    build.build(
      "dockerBatch",
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
      "${buildTimeTemplatesString}",
      "${buildTimeSecretsString}",
      "${config.mixCaseRepo}",
      "${config.testEnv}"
    )
  }
}
