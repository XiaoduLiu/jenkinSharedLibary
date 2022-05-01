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

  def commons = new com.aristotlecap.pipeline.Commons()

  def accountAppfileMapString = commons.getStringFromMap(config.accountAppfileMap)
  def appfileStackMapString = commons.getStringFromMap(config.appfileStackMap)
  def parametersOverridesMapString = commons.getStringFromMap(config.parametersOverridesMap)

  def build = new com.aristotlecap.pipeline.awsCFBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "awsCF",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${nonprodAccountsString}",
        "${prodAccountsString}",
        "${config.releaseVersion}",
        "${accountAppfileMapString}",
        "${appfileStackMapString}",
        "${parametersOverridesMapString}"
      )
    }
  } else {
    build.build(
      "awsCF",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${nonprodAccountsString}",
      "${prodAccountsString}",
      "${config.releaseVersion}",
      "${accountAppfileMapString}",
      "${appfileStackMapString}",
      "${parametersOverridesMapString}"
    )
  }
}
