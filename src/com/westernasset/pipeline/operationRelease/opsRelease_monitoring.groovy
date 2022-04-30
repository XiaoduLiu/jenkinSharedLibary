package com.westernasset.pipeline.operationRelease

import com.westernasset.pipeline.util.ConfluentUtil

def build(params) {

  def didTimeout
  def buildNumber = params.buildNumber
  def crNumber = params.crNumber
  def gitBranchName = params.gitBranchName
  def gitCommit = params.gitCommit
  def gitScm = params.gitScm
  def releaseVersion = params.releaseVersion
  def prodEnv = params.prodEnv
  def values = params.values
  def secrets = params.secrets
  def charts = params.charts
  def dockerfiles = params.dockerfiles
  def builderTag = params.builderTag
  def organizationName = params.organizationName
  def appGitRepoName = params.appGitRepoName
  def namespace = params.namespace
  def helmRepos = params.helmRepos
  def dashboardDeploymentOnly = (params.dashboardDeploymentOnly.equalsIgnoreCase('true')||params.dashboardDeploymentOnly.equalsIgnoreCase('yes'))

  print params
  print dashboardDeploymentOnly

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    didTimeout = false
    def userInput

    currentBuild.displayName = "${gitBranchName}-${buildNumber}-${releaseVersion}-${crNumber}"

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Release?')
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }
  }
  if (didTimeout) {
    // do something on timeout
    echo "no input was received before timeout"
    currentBuild.result = 'SUCCESS'
  } else {
    deploy(buildNumber, crNumber, gitBranchName, gitCommit, gitScm,
           releaseVersion, prodEnv, values, secrets, charts,
           dockerfiles, builderTag, organizationName, appGitRepoName, namespace,
           helmRepos, dashboardDeploymentOnly)
  }
}

def deploy(buildNumber, crNumber, gitBranchName, gitCommit, gitScm,
           releaseVersion, prodEnv, values, secrets, charts,
           dockerfiles, builderTag, organizationName, appGitRepoName, namespace,
           helmRepos, dashboardDeploymentOnly) {
  currentBuild.displayName = "${gitBranchName}-${buildNumber}-${releaseVersion}-${crNumber}"
  def commons = new com.westernasset.pipeline.Commons()
  def helmUtil = new com.westernasset.pipeline.util.HelmUtil()
  def grafana = new com.westernasset.pipeline.util.GrafanaUtil()
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  def prodCloud = commons.getProdCluster(prodEnv);
  podTemplate(
    cloud: "${prodCloud}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    nodeSelector: 'node-role.westernasset.com/builder=true',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-prod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {

      deleteDir()
      git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"

      def tagName = "${gitBranchName}-${releaseVersion}"
      println tagName

      def config=[:]
      config.branch_name = gitBranchName
      config.build_number = buildNumber
      config.builderTag = builderTag

      def strArray = charts.split("\n")
      def arr = []
      strArray.each{ me ->
        println "my map string->" + me
        def obj = helmUtil.getMapFromString(me, ":")
        arr.push(obj)
      }
      config.charts = arr

      config.dockerfiles = helmUtil.getMapFromString(dockerfiles, ":")

      config.secrets = commons.getMapFromString(secrets)
      config.values = commons.getMapFromString(values)

      if (helmRepos.toLowerCase() != 'null') {
        config.helmRepos = helmUtil.getMapFromString(helmRepos, "=")
      }

      config.releaseVersion=releaseVersion
      config.namespace = namespace

      println config

      def repo=[:]
      repo.organizationName = organizationName
      repo.appGitRepoName = appGitRepoName

      println repo

      stage("Deploy to Prod") {
        if (dashboardDeploymentOnly) {
          print 'deploy dashboard only'
          grafana.secretProcessing(config, repo, 'prod', true)
          grafana.deployDashboards('prod')
        } else {
          echo "EXECUTE PROD DEPLOY"
          helmUtil.updateHelmRepos(config)
          helmUtil.helmDeploy(config, repo, 'prod', true)

          //check if the dashboards.yaml is exist
          grafana.deployDashboards('prod')
        }
      }
      //tag the github
      helmUtil.commitRelease(tagName)
    }
  }
}
