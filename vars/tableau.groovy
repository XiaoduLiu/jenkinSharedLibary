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

  def tdsxFilesArray = config.tdsxFiles
  def tdsxFilesString = "null"
  if (tdsxFilesArray != null && tdsxFilesArray.size() > 0) {
     tdsxFilesString = tdsxFilesArray.join("\n")
  }

  def tdsxNamesArray = config.tdsxNames
  def tdsxNamesString = "null"
  if (tdsxNamesArray != null && tdsxNamesArray.size() > 0) {
     tdsxNamesString = tdsxNamesArray.join("\n")
  }

  def tdsxProjectsArray = config.tdsxProjects
  def tdsxProjectsString = "null"
  if (tdsxProjectsArray != null && tdsxProjectsArray.size() > 0) {
     tdsxProjectsString = tdsxProjectsArray.join("\n")
  }

  def tdsxSecretsArray = config.tdsxSecrets
  def tdsxSecretsString = "null"
  if (tdsxSecretsArray != null && tdsxSecretsArray.size() > 0) {
     tdsxSecretsString = tdsxSecretsArray.join("\n")
  }

  def twbFilesArray = config.twbFiles
  def twbFilesString = "null"
  if (twbFilesArray != null && twbFilesArray.size() > 0) {
     twbFilesString = twbFilesArray.join("\n")
  }

  def twbNamesArray = config.twbNames
  def twbNamesString = "null"
  if (twbNamesArray != null && twbNamesArray.size() > 0) {
     twbNamesString = twbNamesArray.join("\n")
  }

  def twbProjectsArray = config.twbProjects
  def twbProjectsString = "null"
  if (twbProjectsArray != null && twbProjectsArray.size() > 0) {
     twbProjectsString = twbProjectsArray.join("\n")
  }

  def twbSecretsArray = config.twbSecrets
  def twbSecretsString = "null"
  if (twbSecretsArray != null && twbSecretsArray.size() > 0) {
     twbSecretsString = twbSecretsArray.join("\n")
  }

  def deleteNamesArray = config.deleteNames
  def deleteNamesString = "null"
  if (deleteNamesArray != null && deleteNamesArray.size() > 0) {
     deleteNamesString = deleteNamesArray.join("\n")
  }

  def deleteFromProjectsArray = config.deleteFromProjects
  def deleteFromProjectsString = "null"
  if (deleteFromProjectsArray != null && deleteFromProjectsArray.size() > 0) {
     deleteFromProjectsString = deleteFromProjectsArray.join("\n")
  }

  def build = new com.aristotlecap.pipeline.tableauBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "tableau",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${nonProdEnvString}",
        "${qaEnvsString}",
        "${config.prodEnv}",
        "${config.drEnv}",
        "${config.releaseVersion}",
        "${tdsxFilesString}",
        "${tdsxNamesString}",
        "${tdsxProjectsString}",
        "${tdsxSecretsString}",
        "${twbFilesString}",
        "${twbNamesString}",
        "${twbProjectsString}",
        "${twbSecretsString}",
        "${deleteNamesString}",
        "${deleteFromProjectsString}"
      )
    }
  } else {
    build.build(
      "tableau",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${nonProdEnvString}",
      "${qaEnvsString}",
      "${config.prodEnv}",
      "${config.drEnv}",
      "${config.releaseVersion}",
      "${tdsxFilesString}",
      "${tdsxNamesString}",
      "${tdsxProjectsString}",
      "${tdsxSecretsString}",
      "${twbFilesString}",
      "${twbNamesString}",
      "${twbProjectsString}",
      "${twbSecretsString}",
      "${deleteNamesString}",
      "${deleteFromProjectsString}"
    )
  }
}
