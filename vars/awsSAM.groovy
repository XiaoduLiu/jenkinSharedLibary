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

  def build = new com.westernasset.pipeline.awsSAMBuild()

  echo "working in side the call(body)"

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "awsSAM",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${nonprodAccountsString}",
        "${prodAccountsString}",
        "${config.releaseVersion}",
        "${config.templateFile}",
        "${config.stackName}"
      )
    }
  } else {
    build.build(
      "awsSAM",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${nonprodAccountsString}",
      "${prodAccountsString}",
      "${config.releaseVersion}",
      "${config.templateFile}",
      "${config.stackName}"
    )
  }
}
