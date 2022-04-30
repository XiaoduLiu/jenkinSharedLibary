package com.westernasset.pipeline.operationRelease;

import com.westernasset.pipeline.operationRelease.*
import com.westernasset.pipeline.models.*
import net.sf.json.JSONObject

def build(params, gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
          crNumber, liquibaseChangeLog, liquibaseBuilderTag, organizationName, appGitRepoName,
          releaseVersion, buildNumber, appDtrRepo, projectType, templates,
          secrets, imageTags, startServerScripts, stopServerScripts, remoteAppUser,
          secretsRemoteDests, appArtifacts, appArtifactsRemoteDests, nonProdHostsMap, prodHosts,
          preInstallArtifacts, preInstallArtifactsDests, upstreamJobName, upstreamBuildNumber, appPath,
          module, fromEnv, toEnv, buildSteps, preStartServerLocalCmds,
          postStartServerLocalCmds, builderTag, jobIdsNonprod, jobIdsProd, deleteNonprodJobIds,
          deleteProdJobIds, postDeploySteps, backupDest, prodBucket, S3KeyMap,
          prodAccounts, accountAppfileMap, appfileStackMap, dockerfileToTagMap,
          templateFile, stackName, parametersOverridesMap, tdsxFiles,
          tdsxNames, tdsxProjects, twbFiles, twbNames,twbProjects,
          tabbedFlag, tdsxSecrets, twbSecrets, deleteNames, deleteFromProjects,
          verifyFromChange, hostDomain, os, scriptRoot, requesterId,
          charts, dockerfiles, namespace, helmRepos, connectors, parameterJsonString) {

  def commons = new com.westernasset.pipeline.Commons()
  def appRoleName = organizationName + '-' + appGitRepoName + '-prod'
  if (projectType == 'juliaDockerService') {
    def opsRelease = new opsRelease_juliaDockerService()
    opsRelease.release(params)
  } else if (projectType == 'juliaDockerBatch') {
    def opsRelease = new opsRelease_juliaDockerBatch()
    opsRelease.release(params)
  } else if (projectType == 'gradleDockerService') {
    def opsRelease = new opsRelease_gradleDockerService()
    opsRelease.build(params)
  } else if (projectType == 'liquibase') {
    def build_liquibase = new com.westernasset.pipeline.operationRelease.opsRelease_liquibase()
    build_liquibase.build(gitScm, gitBranchName, gitCommit,
                          crNumber, liquibaseChangeLog, liquibaseBuilderTag, organizationName, appGitRepoName,
                          releaseVersion, buildNumber, appDtrRepo, projectType, imageTags, templates)
  } else if (projectType == 'dockerImages') {
    def build_dockerImages = new com.westernasset.pipeline.operationRelease.opsRelease_dockerImages()
    build_dockerImages.build(gitBranchName, buildNumber, imageTags, gitCommit, gitScm, organizationName, appGitRepoName, appDtrRepo)
  } else if (projectType == 'mavenDockerBatch' || projectType == 'dockerBatch') {
    def build_batch = new com.westernasset.pipeline.operationRelease.opsRelease_batch()
    build_batch.build(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
                crNumber, organizationName, appGitRepoName,
                releaseVersion, buildNumber, appDtrRepo, projectType, templates,
                secrets, imageTags)
  } else if (projectType == 'mavenSSHDeploy'  || projectType == 'SSHDeploy') {
    def build_ssh = new com.westernasset.pipeline.operationRelease.opsRelease_SSH()
    build_ssh.build(gitBranchName, buildNumber, imageTags, gitCommit, gitScm,
                    organizationName, appGitRepoName, templates, secrets, appDtrRepo,
                    startServerScripts, stopServerScripts, remoteAppUser, secretsRemoteDests, appArtifacts,
                    appArtifactsRemoteDests, nonProdHostsMap, prodHosts, preInstallArtifacts, preInstallArtifactsDests,
                    upstreamJobName, upstreamBuildNumber, crNumber, releaseVersion, buildSteps,
                    preStartServerLocalCmds, postStartServerLocalCmds, builderTag, backupDest, projectType,
                    liquibaseChangeLog, liquibaseBuilderTag, hostDomain, os)
  } else if (projectType == 'mavenDockerService' || projectType == 'dockerService' || projectType == 'dockerMultiService') {
    def build_service = new com.westernasset.pipeline.operationRelease.opsRelease_service()
    build_service.build(gitScm, gitBranchName, gitCommit, prodEnv, drEnv,
                        crNumber, liquibaseChangeLog, liquibaseBuilderTag, organizationName, appGitRepoName,
                        releaseVersion, buildNumber, appDtrRepo, projectType, templates,
                        secrets, imageTags, postDeploySteps, dockerfileToTagMap)
  } else if (projectType == 'databricksWorkbookCopy')  {
    def build_databrick = new com.westernasset.pipeline.operationRelease.opsRelease_databrickWorkbookCopy()
    build_databrick.build(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag, appPath, module, fromEnv, toEnv, releaseVersion, gitCommit, gitScm, crNumber)
  } else if (projectType == 'databrickJobDeploy')  {
    def build_databrickJobDeploy = new com.westernasset.pipeline.operationRelease.opsRelease_databrickJobDeploy()
    build_databrickJobDeploy.build(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag,
                                   jobIdsNonprod, jobIdsProd, deleteNonprodJobIds, deleteProdJobIds, releaseVersion,
                                   gitCommit, gitScm, crNumber)
  } else if ( projectType == 'mavenS3Deploy') {
    def build_mavenS3Deploy = new com.westernasset.pipeline.operationRelease.opsRelease_mavenS3Deploy()
    build_mavenS3Deploy.build(gitBranchName, gitScm, gitCommit, buildNumber, organizationName,
                              appGitRepoName, appArtifacts, prodBucket, S3KeyMap, releaseVersion,
                              upstreamJobName, upstreamBuildNumber, crNumber)
  } else if ( projectType == 'awsCDK') {
    def build_awsCDK = new com.westernasset.pipeline.operationRelease.opsRelease_awsCDK()
    build_awsCDK.build(gitBranchName, buildNumber, organizationName, appGitRepoName,
                       gitScm, gitCommit, builderTag, prodAccounts, releaseVersion,
                       accountAppfileMap, appfileStackMap, crNumber,
                       templates, secrets)
  } else if ( projectType == 'awsSAM') {
    def build_awsSAM = new com.westernasset.pipeline.operationRelease.opsRelease_awsSAM()
    build_awsSAM.build(projectType, gitBranchName, buildNumber, builderTag, prodAccounts,
                       releaseVersion, templateFile, stackName, organizationName, appGitRepoName,
                       gitScm, gitCommit, crNumber)
  } else if ( projectType == 'awsCF') {
    def build_awsCF = new com.westernasset.pipeline.operationRelease.opsRelease_awsCF()
    build_awsCF.build(gitBranchName, buildNumber, organizationName, appGitRepoName, gitScm,
                      gitCommit, builderTag, prodAccounts, releaseVersion, accountAppfileMap,
                      appfileStackMap, crNumber, parametersOverridesMap)
  } else if ( projectType == 'tableau') {
    def build_tableau = new com.westernasset.pipeline.operationRelease.opsRelease_tableau()
    build_tableau.build(projectType, gitBranchName, buildNumber, builderTag, releaseVersion,
                        tdsxFiles, tdsxNames, tdsxProjects, tdsxSecrets, twbFiles, twbNames,
                        organizationName, appGitRepoName, gitScm, gitCommit, twbProjects,
                        crNumber, tabbedFlag, twbSecrets, deleteNames, deleteFromProjects)
  } else if ( projectType == 'sqitch') {
    def build_sqitch = new com.westernasset.pipeline.operationRelease.opsRelease_sqitch()
    build_sqitch.build(projectType, gitBranchName, buildNumber, builderTag, releaseVersion,
                      organizationName, appGitRepoName, gitScm, gitCommit, crNumber,
                      verifyFromChange)
  } else if (projectType == 'awsFargateAlb') {
    def build_awsFargateAlb = new com.westernasset.pipeline.operationRelease.opsRelease_awsFargateAlb();
    build_awsFargateAlb.build(buildNumber, crNumber, gitBranchName, gitCommit, gitScm,
                              organizationName, appGitRepoName, releaseVersion, budgetCode)
  } else if (projectType == "scriptExecutor") {
    println "working here before call the projectType ---> scriptExecutor"
    def build_scriptExecutor = new com.westernasset.pipeline.operationRelease.opsRelease_scriptExecutor()
    build_scriptExecutor.build(buildNumber, crNumber, gitBranchName, gitCommit, gitScm, releaseVersion, builderTag, scriptRoot)
  } else if (projectType == "awsSftpTransferUsers") {
    println "working here before call the projectType ---> awsSftpTransferUsers"
    def build_awsSftpTransferUsers = new com.westernasset.pipeline.operationRelease.opsRelease_awsSftpTransferUsers()
    build_awsSftpTransferUsers.build(builderTag, buildNumber, crNumber, organizationName, appGitRepoName, gitScm, requesterId)
  } else if (projectType == 'helm') {
    println "working here before call the projectType ---> helm"
    def build_helm = new com.westernasset.pipeline.operationRelease.opsRelease_helm()
    println "working here again before call"
    build_helm.build(params)
  } else if (projectType == 'confluentConnector') {
    println "working here before call the projectType ---> confluentConnector"
    def build_confluentConnector = new com.westernasset.pipeline.operationRelease.opsRelease_confluentConnector()
    build_confluentConnector.build(buildNumber, crNumber, gitBranchName, gitScm,
                                   connectors, builderTag,  organizationName, appGitRepoName)
  } else if (projectType == 'tableauApi') {
    println "working here before call the projectType ---> tableauApi"
    def tb = new opsRelease_tableauApi()
    tb.build(params)
  } else if (projectType == 'awsEksctl') {
    println "working here before call the projectType ---> awsEksctl"
    def eks = new opsRelease_awsEksctl()
    eks.build(params)
  } else if (projectType == 'monitoring') {
    println "working here before call the projectType --> monitoring"
    def m = new opsRelease_monitoring()
    m.build(params)
  } else if (projectType == 'awsApp') {
    println "working here before call the projectType --> awsAPp"
    def a = new com.westernasset.pipeline.operationRelease.opsRelease_awsApp()
    a.build(params)
  } else if (projectType == 'helmDeploy') {
    println "working here before call the projectType --> helmDeploy"
    def helmDeploy = new com.westernasset.pipeline.operationRelease.opsRelease_helmDeploy()
    helmDeploy.build(params)
  } else if (projectType == 'ccloudTopics') {
    println "working here before call the projectType --> ccloudTopics"
    def ccloudTopics = new com.westernasset.pipeline.operationRelease.opsRelease_ccloudTopics()
    ccloudTopics.build(params)
  } else if (projectType == 'mavenKafkaSchema') {
    println "working here before call the projectType --> mavenKafkaSchema"
    def mavenKafkaSchema = new com.westernasset.pipeline.operationRelease.opsRelease_mavenKafkaSchema()
    mavenKafkaSchema.build(params)
  } else if (projectType == 'kafkaSchema') {
    println "working here before call the projectType --> kafkaSchema"
    def kafkaSchema = new com.westernasset.pipeline.operationRelease.opsRelease_kafkaSchema()
    kafkaSchema.build(params)
  } else if (projectType == 'kdaApp') {
    println "working here before call the projectType --> kdaApp"
    def kdaApp = new com.westernasset.pipeline.operationRelease.opsRelease_kdaApp()
    kdaApp.build(params)
  } else if (projectType == "ccloud") {
    println "working here before call the projectType --> ccloud"
    def ccloud = new com.westernasset.pipeline.operationRelease.opsRelease_ccloud()
    ccloud.build(params)
  }
}
