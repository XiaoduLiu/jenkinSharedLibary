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

  def buildStepsArray = config.buildSteps
  def buildStepsString = "null"
  if (buildStepsArray != null && buildStepsArray.size() > 0) {
    buildStepsString = buildStepsArray.join("\n")
  }

  def build = new com.westernasset.pipeline.liquibaseBuild()

  def commons = new com.westernasset.pipeline.Commons()
  def templatesMapString = commons.getStringFromMap(config.templates)

  println "templatesMapString ->" + templatesMapString

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "liquibase",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${nonProdEnvString}",
        "${config.liquibaseChangeLog}",
        "${config.liquibaseBuilderTag}",
        "${qaEnvsString}",
        "${config.releaseVersion}",
        "${templatesMapString}"
      )
    }
  } else {
    build.build(
      "liquibase",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${nonProdEnvString}",
      "${config.liquibaseChangeLog}",
      "${config.liquibaseBuilderTag}",
      "${qaEnvsString}",
      "${config.releaseVersion}",
      "${templatesMapString}"
    )
  }
}
