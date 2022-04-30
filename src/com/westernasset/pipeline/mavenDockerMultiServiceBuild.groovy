package com.westernasset.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber,
         builderTag, nonProdEnvs, liquibaseChangeLog, liquibaseBuilderTag,
         qaEnvs, prodEnv, drEnv, releaseVersion, templates,
         secrets, dockerfileToTagMapString) {

  def gitCommit
  def pomversion

  def projectType = "${projectTypeParam}"
  def imageTag = "${gitBranchName}-${buildNumber}"
  currentBuild.displayName = imageTag

  def organizationName
  def appGitRepoName
  def appDtrRepo
  def gitScm

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-sencha-cache', mountPath: '/home/jenkins/senchaBuildCache'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
    ]) {
      node(POD_LABEL) {
        def commons = new com.westernasset.pipeline.Commons()

        try {
          stage ('Clone') {
          // Clean workspace before doing anything
          deleteDir()
          checkout scm

          gitCommit=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
          echo gitCommit

          String gitRemoteURL = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
          echo gitRemoteURL

          gitScm = "git@github.westernasset.com:" + gitRemoteURL.drop(32)
          echo gitScm

          String shortName = gitRemoteURL.drop(32).reverse().drop(4).reverse()
          echo shortName

          def names = shortName.split('/')

          echo names[0]
          echo names[1]

          organizationName = names[0]
          appGitRepoName = names[1]

          appDtrRepo = organizationName + '/' + appGitRepoName
          echo "appDtrRepo -> ${appDtrRepo}"

        }

        echo sh(script: 'env|sort', returnStdout: true)

        imageTag = commons.setJobLabelJavaProject(gitBranchName, buildNumber)
        commons.mavenSnapshotBuild()

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        def dockerfileToTagMap = commons.getMapFromString(dockerfileToTagMapString)
        dockerfileToTagMap.each{ dockerFile, tag ->
          def dockerfilefullpath = "${workspace}/${dockerFile}"
          commons.hadolintDockerFile("${dockerFile}")
          commons.dockerBuildForMultiImages("${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_NONPROD_KEY}", "${appDtrRepo}", "${tag}", organizationName, appGitRepoName, gitBranchName, gitCommit, dockerfilefullpath, buildNumber, 'Docker Build')
        }

        build job: "${env.siteDeployJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
          [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
          [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
          [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)]
        ]

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }

  nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                nonProdEnvs, liquibaseChangeLog, liquibaseBuilderTag, qaEnvs, prodEnv,
                drEnv, releaseVersion, organizationName, appGitRepoName, appDtrRepo,
                gitCommit, templates, secrets, imageTag, dockerfileToTagMapString)
}

def nonprodDeploy(projectTypeParam, gitBranchName, buildNumber, builderTag, gitScm,
                  nonProdEnvs, liquibaseChangeLog, liquibaseBuilderTag, qaEnvs, prodEnv,
                  drEnv, releaseVersion, organizationName, appGitRepoName, appDtrRepo,
                  gitCommit, templates, secrets, imageTag, dockerfileToTagMapString) {


  def didAbort = false
  def didTimeout = false

  def userInput
  def deployEnv
  def releaseFlag

  currentBuild.displayName = imageTag

  stage("Should I deploy to Non-Prod?") {
    checkpoint "Deploy to Non-Prod"

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(
          id: 'Proceed1', message: 'Approve Non-Prod Deploy?', parameters: [
            [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Ready for Maven Release?', name: 'releaseFlag'],
            [$class: 'ChoiceParameterDefinition', choices: nonProdEnvs, description: 'Environments', name: 'env']
        ])
      }
      deployEnv = userInput['env']
      println deployEnv

      releaseFlag = userInput['releaseFlag']
      println releaseFlag

    } catch(err) { // timeout reached or input false
      def user = err.getCauses()[0].getUser()
      if('SYSTEM' == user.toString()) { // SYSTEM means timeout.
        didTimeout = true
      } else {
        didAbort = true
        echo "Aborted by: [${user}]"
      }
    }
  }

  if (didTimeout) {
    // do something on timeout
    echo "no input was received before timeout"
    currentBuild.result = 'SUCCESS'
  } else if (didAbort) {
    // do something else
    echo "this was not successful"
    currentBuild.result = 'SUCCESS'
  } else {
    nonProdDeployLogic(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                       deployEnv, organizationName, appGitRepoName, liquibaseChangeLog, builderTag,
                       "${env.liquibaseProjectFolder}", liquibaseBuilderTag, imageTag, templates, secrets,
                       releaseFlag, nonProdEnvs, qaEnvs, prodEnv, drEnv,
                       releaseVersion, appDtrRepo, dockerfileToTagMapString)
  }
}

def nonProdDeployLogic(projectTypeParam, gitScm, gitBranchName, gitCommit, buildNumber,
                       deployEnvironment, organizationName, appGitRepoName, liquibaseChangeLog, builderTag,
                       liquibaseProjectFolder, liquibaseBuilderTag, imageTag, templates,secrets,
                       releaseFlag, nonProdEnvs, qaEnvs, prodEnv, drEnv,
                       releaseVersion, appDtrRepo, dockerfileToTagMapString) {

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  def liquibaseImage = "${env.TOOL_BUSYBOX}"
  if (liquibaseBuilderTag != null && liquibaseBuilderTag != 'null') {
    liquibaseImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${liquibaseBuilderTag}"
  }

  def released = false;

  def tag;
  def nonProdDeployDisplayTag;

  def build = new com.westernasset.pipeline.devRelease.releaseBuild_mavenDocker()

  def commons = new com.westernasset.pipeline.Commons()
  def envCluster = commons.getNonProdEnvDetailsForService(deployEnvironment)
  def deployEnv = envCluster.deployEnv
  def clusterName = (envCluster.clusterName != null)? envCluster.clusterName: 'pas-development'

  podTemplate(
    cloud: "${clusterName}",
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'liquibase', image: "${liquibaseImage}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'kubectl', image: "${env.TOOL_KUBECTL}", ttyEnabled: true),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh'),
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
  ]) {
    node(POD_LABEL) {
      currentBuild.displayName = imageTag + '-' + deployEnv
      nonProdDeployDisplayTag = currentBuild.displayName

      echo currentBuild.displayName
      deleteDir()
      checkout scm
      sh "git reset --hard ${gitCommit}"

      def qaPassFlag = commons.releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs)
      echo "qaPassFlag::::"
      if (qaPassFlag) {
        echo "qaPassFlag is true!!!"
      } else {
        echo "qaPassFlag is false!!!"
      }

      commons.nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                                 organizationName, appGitRepoName, liquibaseChangeLog, "${env.IMAGE_REPO_URI}", liquibaseProjectFolder,
                                 imageTag, templates, secrets, dockerfileToTagMapString, null)

      if (qaPassFlag) {
        stage('Build Release') {
         //echo buildNumber

         tagAndDisplay = build.build(gitBranchName, buildNumber, builderTag, gitScm, gitCommit,
                                     projectTypeParam, appDtrRepo, organizationName, appGitRepoName, prodEnv,
                                     drEnv, liquibaseChangeLog, liquibaseBuilderTag, releaseVersion, imageTag,
                                     templates, secrets, nonProdEnvs, 'null', dockerfileToTagMapString)

          def arr = tagAndDisplay.tokenize('::')
          tag = arr[0]
          nonProdDeployDisplayTag = arr[1]
          released = true
        }
      }
    }
  }

  if (released) {
    def qaApprove = new com.westernasset.pipeline.qa.qaApprove()
    qaApprove.approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
                      imageTag, organizationName, appGitRepoName, prodEnv, drEnv,
                      liquibaseChangeLog, liquibaseBuilderTag, projectTypeParam, templates, secrets,
                      'null', dockerfileToTagMapString, nonProdDeployDisplayTag, 'null')

    if (projectTypeParam == 'javaApp') {
      build job: "${env.nonProdReleaseDeployJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
        [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(imageTag)],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
        [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
        [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
        [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
        [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
        [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
        [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: String.valueOf(liquibaseChangeLog)],
        [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: String.valueOf(liquibaseBuilderTag)],
        [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
        [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
        [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
        [$class: 'StringParameterValue', name: 'nonProdEnvs', value: String.valueOf(nonProdEnvs)],
        [$class: 'StringParameterValue', name: 'releaseImageTag', value: String.valueOf(imageTag)],
        [$class: 'StringParameterValue', name: 'dockerfileToTagMap', value: String.valueOf(dockerfileToTagMapString)]
      ]
    }
  }
}
