package com.westernasset.pipeline;

import com.westernasset.pipeline.steps.Hadolint

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonProdEnvs,
          qaEnvs, prodEnv, drEnv, releaseVersion, secrets, buildSteps, mixCaseRepo, postDeploySteps, e2eEnv, e2eTestSteps, helmChartVersion) {

  def pomversion

  def imageTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = imageTag

  def commons = new com.westernasset.pipeline.Commons()
  def helm = new com.westernasset.pipeline.util.HelmUtil()

  def repo

  def hadolint = new Hadolint()

  def builderImage = (builderTag != 'null') ? "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}" : env.TOOL_BUSYBOX
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    nodeSelector: 'node-role.westernasset.com/builder=true',
    containers: [
      containerTemplate(name: 'jnlp', image: env.TOOL_AGENT, args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: builderImage, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: env.TOOL_DOCKER, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: env.TOOL_VAULT, ttyEnabled: true),
      containerTemplate(name: 'helm', image: "${env.TOOL_HELM}", ttyEnabled: true),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
      containerTemplate(name: 'sonar', image: env.TOOL_SONAR_SCANNER, ttyEnabled: true, command: 'cat'),
      hadolint.containerTemplate()
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
    ]) {
    node(POD_LABEL) {
      try {
        repo = commons.clone(mixCaseRepo)

        imageTag = commons.setJobLabelNonJavaProject(gitBranchName, repo.gitCommit, buildNumber, releaseVersion)

        print "imageTag --->" + imageTag

        commons.localBuildStepsForDockerServiceBuild("Build & Test", buildSteps)
        commons.sonarProcess(gitBranchName)

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()

        if (fileExists("${workspace}/Dockerfile")) {
          echo 'Yes, there is a Dockerfile exist at the project root and there is a docker build'
          hadolint.lint()
          commons.dockerBuild(env.IMAGE_REPO_URI, env.IMAGE_REPO_NONPROD_KEY, repo.appDtrRepo,
                              imageTag, repo.organizationName, repo.appGitRepoName,
                              gitBranchName, repo.gitCommit, 'null', 'Docker Build')
          if (e2eEnv != null) {
            helm.nonProdDeployLogic(repo.gitScm, env.BRANCH_NAME, repo.gitCommit, env.BUILD_NUMBER, e2eEnv,
                                    repo.organizationName, repo.appGitRepoName, env.IMAGE_REPO_URI,
                                    imageTag, secrets, null, null, "Deploy to E2E", helmChartVersion)

            stage('E2E test') {
              sh(script: 'sleep 10', label: 'sleep')
              container("builder") {
                e2eTestSteps.each { script ->
                  println "script -> ${script}"
                  def testStatus = sh(script: "${script}", label: 'e2e test', returnStatus: true)
                  if (testStatus != 0) {
                    error("e2e test failed")
                  }
                }
              }
            }
          }
        } else {
          echo 'No, there is no DockerFile exist at the project root and there is no docker build'
        }

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonProdEnvs, true, false, false, currentBuild.displayName, "Should I deploy to Non-Prod?", "Approve Non-Prod Deploy?", "Ready for Release")
  if (gate.deployEnv != null) {
    nonProdDeploy(projectTypeParam, repo.gitScm, gitBranchName, repo.gitCommit, buildNumber,
                  gate.deployEnv, repo.organizationName, repo.appGitRepoName, builderTag, secrets,
                  gate.releaseFlag, nonProdEnvs, qaEnvs, prodEnv, drEnv,
                  releaseVersion, repo.appDtrRepo, imageTag, postDeploySteps, helmChartVersion)
  }
}

def nonProdDeploy(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                  deployEnvironment, organizationName, appGitRepoName, builderTag, secrets,
                  releaseFlag, nonProdEnvs, qaEnvs, prodEnv, drEnv,
                  releaseVersion, appDtrRepo, imageTag, postDeploySteps, helmChartVersion) {

  def builderImage = "${env.TOOL_BUSYBOX}"
  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  }

  def tag;
  def nonProdDeployDisplayTag;
  def qaPassFlag

  def commons = new com.westernasset.pipeline.Commons()
  def helm = new com.westernasset.pipeline.util.HelmUtil()
  def envCluster = commons.getNonProdEnvDetailsForService(deployEnvironment)
  def deployEnv = envCluster.deployEnv
  def clusterName = (envCluster.clusterName != null)? envCluster.clusterName: 'pas-development'

  podTemplate(
    cloud: "${clusterName}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    nodeSelector: 'node-role.westernasset.com/builder=true',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'builder', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'helm', image: "${env.TOOL_HELM}", ttyEnabled: true),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {

      echo currentBuild.displayName
      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      currentBuild.displayName = imageTag + '-' + deployEnv

      qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
      echo "qaPassFlag::::"
      if (qaPassFlag) {
        echo "true!!!"
      } else {
        echo "false!!!"
      }

      helm.nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                              organizationName, appGitRepoName, env.IMAGE_REPO_URI, imageTag, secrets,
                              null, null, helmChartVersion)
    }
  }

  def keys = secrets.collect { it.key }
  def vals = secrets.collect { it.value }

  println keys
  println vals

  def templatesStr = helm.getJoinList(keys)
  println templatesStr
  def secretsStr = helm.getJoinList(vals)
  println secretsStr

  if (qaPassFlag) {
    def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
    def gate = gateutil.gate(null, false, true, false, "${imageTag}", 'Approve Release?')
    if (gate.crNumber != null) {
      approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
              imageTag, organizationName, appGitRepoName, prodEnv, drEnv,
              projectTypeParam, templatesStr, secretsStr,
              releaseVersion,postDeploySteps, helmChartVersion, gate.crNumber)
    }
  }
}

def approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
        imageTag, organizationName, appGitRepoName, prodEnv, drEnv,
        projectTypeParam, templates, secrets,
        releaseVersion, postDeploySteps, helmChartVersion, crNumber) {

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
  ]) {

    node(POD_LABEL) {

      stage("QA approve") {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        //tagging
        currentBuild.displayName = "${imageTag}-${crNumber}"
        def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}"
        sh """
          git config --global user.email "jenkins@westernasset.com"
          git config --global user.name "Jenkins Agent"
          git config --global http.sslVerify false
          git config --global push.default matching
          git config -l

          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${crNumber}" '
          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
        """

        //moving images
        def image = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${appDtrRepo}:${gitBranchName}-${releaseVersion}-${buildNumber}"
        println image
        def crTag = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
        println crTag

        println 'need to push this crTag to non prod'
        def approveImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${appDtrRepo}:${crTag}"
        println  approveImage

        def repoNameLower = appGitRepoName.toLowerCase().replace('.', '-')

        def releaseImage1 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${gitBranchName}-${releaseVersion}-${buildNumber}"
        def releaseImage2 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${crTag}"

        pushImageToProdDtr(image, approveImage)
        pushImageToProdDtr(image, releaseImage1)
        pushImageToProdDtr(image, releaseImage2)

        stage('trigger downstream job') {
          build job: "${env.opsReleaseJob}", wait: false, parameters: [
            [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
            [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)],
            [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(crNumber)],
            [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
            [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
            [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
            [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
            [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
            [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
            [$class: 'StringParameterValue', name: 'prodEnv', value: String.valueOf(prodEnv)],
            [$class: 'StringParameterValue', name: 'drEnv', value: String.valueOf(drEnv)],
            [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
            [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
            [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
            [$class: 'StringParameterValue', name: 'postDeploySteps', value: String.valueOf(postDeploySteps)],
            [$class: 'StringParameterValue', name: 'helmChartVersion', value: helmChartVersion]
          ]
        }
      }
    }
  }
}

def pushImageToProdDtr(image, approveImage) {
  container('docker') {
    docker.withRegistry("https://${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_CREDENTIAL}") {
      sh """
        docker pull $image
        docker tag $image $approveImage
        docker push $approveImage
      """
    }
  }
}
