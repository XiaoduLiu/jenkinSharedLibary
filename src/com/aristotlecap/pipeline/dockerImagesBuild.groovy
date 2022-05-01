package com.aristotlecap.pipeline;

def build(config) {

  withFolderProperties {
    echo("Foo: ${env.TOOL_DOCKER}")
    podTemplate(
            cloud: 'us-west-2-devops',
            serviceAccount: 'jenkins',
            namespace: 'jenkins',
            nodeSelector: 'intent=devops-spot',
            containers: [
                    containerTemplate(name: 'maven', image: 'maven:3.8.1-jdk-8', command: 'sleep', args: '99d'),
                    containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
                    containerTemplate(name: 'golang', image: 'golang:1.16.5', command: 'sleep', args: '99d')
            ],
            volumes: [
                    hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
            ]) {
      node(POD_LABEL) {
        stage('Clone') {
          deleteDir()
          checkout scm
          sh "ls -la"
        }
        stage('docker') {
          print config
          container('docker') {
            config.dockerfileMap.each { k, v ->
              println "${k}:${v}"

              String folder = k.split("/")[0]
              String dockerfile = k.split("/")[1]
              println "${folder}"

              docker.withRegistry("https://${env.DOCKER_RELEASES}", "${env.DOCKER_RELEASES_CRED}") {
                sh """
                  cd $folder
                  sleep 600
                  ls -la
                  docker build -t $v -f ./$dockerfile .
                  docker tag $v $env.DOCKER_RELEASES/$v
                  docker push $env.DOCKER_RELEASES/$v
                """
              }
            }
          }
        }
      }
    }
  }

}

def build(gitBranchName, buildNumber, dockerFileImageMap, templatesString, secretsString, preserveRootContext) {

  def gitTag
  def repo
  def imageTags = dockerFileImageMap.collect({entry -> entry.value}).join("\n")

  podTemplate(
    cloud: 'pas-development',
    containers: [
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')]) {
    node(POD_LABEL) {
      try {

        def commons = new com.aristotlecap.pipeline.Commons()
        repo = commons.clone()

        gitTag = commons.getImagesTags(imageTags)
        currentBuild.displayName = "${gitBranchName}-${buildNumber}-${gitTag}"

        if(templatesString != 'null') {
          commons.secretProcess(templatesString, templatesString, 'all', repo.organizationName, repo.appGitRepoName, false)
        }

        container('docker') {
          //do docker build
          stage ('Docker Build') {
            echo "EXECUTE DOCKER BUILD & PUSH TO DTR"
            docker.withRegistry("https://${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_CREDENTIAL}") {
              dockerFileImageMap.each { dockerfile, tag ->
                if (!tag.contains(':')) {
                  tag = repo.appGitRepoName + ":" + tag
                  dockerFileImageMap.put(dockerfile, tag)
                }
                commons.hadolintDockerFile("$dockerfile")
                def imageTag = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${repo.organizationName}/${tag}"
                commons.removeTag(imageTag)
                def buildContext = (dockerfile.contains('/') && !preserveRootContext) ? commons.findPath(dockerfile) : "."
                sh """
                  docker build -t $imageTag -f $dockerfile $buildContext
                  docker push $imageTag
                """
              }
              imageTags = dockerFileImageMap.collect({entry -> entry.value}).join("\n")
            }
          }
        }

        //remove the templates files
        commons.deleteSecretFiles(secretsString)
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  promoteToProduction(gitBranchName, buildNumber, imageTags,
                      repo.gitCommit, repo.gitScm, repo.organizationName, repo.appGitRepoName,
                      repo.appDtrRepo, gitTag)
}

def promoteToProduction(gitBranchName, buildNumber, imageTags,
                        gitCommit, gitScm, organizationName, appGitRepoName,
                        appDtrRepo, gitTag) {
  currentBuild.description = buildDescription(imageTags.split('\n'))
  currentBuild.displayName = "${gitBranchName}-${buildNumber}-${gitTag}"
  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, false, false, currentBuild.displayName, 'Release to Production?', 'Approve Release?')
  if (!gate.abortedOrTimeoutFlag) {
    stage('Trigger Downstream Job') {
      build job: "${env.opsReleaseJob}", wait: false, parameters: [
        [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
        [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
        [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
        [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
        [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
        [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
        [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
        [$class: 'StringParameterValue', name: 'projectType', value: 'dockerImages'],
        [$class: 'StringParameterValue', name: 'imageTags', value: String.valueOf(imageTags)]
      ]
    }
  }
}

def buildDescription(imageTags) {
  def description = "<ul>"
  imageTags.each { imageTag ->
    description = description + "<li>" + imageTag + "</li>"
  }
  return description + "</ul>"
}
