#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def nonprodAccountsArray = config.nonprodAccounts
  def nonprodAccountsString = "null"
  if (nonprodAccountsArray != null && nonprodAccountsArray.size() > 0) {
     nonprodAccountsString = nonprodAccountsArray.join("\n")
  }

  def prodAccountsArray = config.prodAccounts
  def prodAccountsString = "null"
  if (prodAccountsArray != null && prodAccountsArray.size() > 0) {
     prodAccountsString = prodAccountsArray.join("\n")
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

  def commons = new com.westernasset.pipeline.Commons()

  def accountAppfileMapString = commons.getStringFromMap(config.accountAppfileMap)
  def appfileStackMapString = commons.getStringFromMap(config.appfileStackMap)

  def build = new com.westernasset.pipeline.awsCDKBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "awsCDK",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${nonprodAccountsString}",
        "${prodAccountsString}",
        "${config.releaseVersion}",
        "${accountAppfileMapString}",
        "${appfileStackMapString}",
        "${templatesString}",
        "${secretsString}",
        "${config.publishAsNpmMoudule}"
      )
    }
  } else {
    build.build(
      "awsCDK",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${nonprodAccountsString}",
      "${prodAccountsString}",
      "${config.releaseVersion}",
      "${accountAppfileMapString}",
      "${appfileStackMapString}",
      "${templatesString}",
      "${secretsString}",
      "${config.publishAsNpmMoudule}"
    )
  }
}
