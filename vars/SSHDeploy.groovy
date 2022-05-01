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

  def secretsRemoteDestsArray = config.secretsRemoteDests
  def secretsRemoteDestsString = "null"
  if (secretsRemoteDestsArray != null && secretsRemoteDestsArray.size() > 0) {
     secretsRemoteDestsString = secretsRemoteDestsArray.join("\n")
  }

  def appArtifactsArray = config.appArtifacts
  def appArtifactsString = "null"
  if (appArtifactsArray != null && appArtifactsArray.size() > 0) {
     appArtifactsString = appArtifactsArray.join("\n")
  }

  def appArtifactsRemoteDestsArray = config.appArtifactsRemoteDests
  def appArtifactsRemoteDestsString = "null"
  if (appArtifactsRemoteDestsArray != null && appArtifactsRemoteDestsArray.size() > 0) {
     appArtifactsRemoteDestsString = appArtifactsRemoteDestsArray.join("\n")
  }

  def startServerScriptsArray = config.startServerScripts
  def startServerScriptsString = "null"
  if (startServerScriptsArray != null && startServerScriptsArray.size() > 0) {
     startServerScriptsString = startServerScriptsArray.join("\n")
  }

  def stopServerScriptsArray = config.stopServerScripts
  def stopServerScriptsString = "null"
  if (stopServerScriptsArray != null && stopServerScriptsArray.size() > 0) {
     stopServerScriptsString = stopServerScriptsArray.join("\n")
  }

  def buildStepsArray = config.buildSteps
  def buildStepsString = "null"
  if (buildStepsArray != null && buildStepsArray.size() > 0) {
    buildStepsString = buildStepsArray.join("\n")
  }

  def preInstallArtifactsArray = config.preInstallArtifacts
  def preInstallArtifactsString = "null"
  if (preInstallArtifactsArray != null && preInstallArtifactsArray.size() > 0) {
     preInstallArtifactsString = preInstallArtifactsArray.join("\n")
  }

  def preInstallArtifactsDestsArray = config.preInstallArtifactsDests
  def preInstallArtifactsDestsString = "null"
  if (preInstallArtifactsDestsArray != null && preInstallArtifactsDestsArray.size() > 0) {
     preInstallArtifactsDestsString = preInstallArtifactsDestsArray.join("\n")
  }

  def preStartServerLocalCmdsArray = config.preStartServerLocalCmds
  def preStartServerLocalCmdsString = "null"
  if (preStartServerLocalCmdsArray != null && preStartServerLocalCmdsArray.size() > 0) {
     preStartServerLocalCmdsString = preStartServerLocalCmdsArray.join("\n")
  }

  def postStartServerLocalCmdsArray = config.postStartServerLocalCmds
  def postStartServerLocalCmdsString = "null"
  if (postStartServerLocalCmdsArray != null && postStartServerLocalCmdsArray.size() > 0) {
     postStartServerLocalCmdsString = postStartServerLocalCmdsArray.join("\n")
  }

  def build = new com.aristotlecap.pipeline.SSHDeployBuild()

  def commons = new com.aristotlecap.pipeline.Commons()
  def nonProdHostsMapString = commons.getStringFromMap(config.nonProdHostsMap)

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "SSHDeploy",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${nonProdEnvString}",
        "${qaEnvsString}",
        "${nonProdHostsMapString}",
        "${config.prodHosts}",
        "${config.remoteAppUser}",
        "${preInstallArtifactsString}",
        "${preInstallArtifactsDestsString}",
        "${templatesString}",
        "${secretsString}",
        "${secretsRemoteDestsString}",
        "${stopServerScriptsString}",
        "${startServerScriptsString}",
        "${appArtifactsString}",
        "${appArtifactsRemoteDestsString}",
        "${config.releaseVersion}",
        "${buildStepsString}",
        "${preStartServerLocalCmdsString}",
        "${postStartServerLocalCmdsString}",
        "${config.builderTag}",
        "${config.backupDest}"
      )
    }
  } else {
    build.build(
      "SSHDeploy",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${nonProdEnvString}",
      "${qaEnvsString}",
      "${nonProdHostsMapString}",
      "${config.prodHosts}",
      "${config.remoteAppUser}",
      "${preInstallArtifactsString}",
      "${preInstallArtifactsDestsString}",
      "${templatesString}",
      "${secretsString}",
      "${secretsRemoteDestsString}",
      "${stopServerScriptsString}",
      "${startServerScriptsString}",
      "${appArtifactsString}",
      "${appArtifactsRemoteDestsString}",
      "${config.releaseVersion}",
      "${buildStepsString}",
      "${preStartServerLocalCmdsString}",
      "${postStartServerLocalCmdsString}",
      "${config.builderTag}",
      "${config.backupDest}"
    )
  }
}
