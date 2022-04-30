package com.westernasset.pipeline.operationRelease;

def build(params) {

  def didTimeout = false
  def buildNumber = params.buildNumber
  def crNumber = params.crNumber
  def gitBranchName = params.gitBranchName
  def gitCommit = params.gitCommit
  def gitScm = params.gitScm
  def releaseVersion = params.releaseVersion
  def organizationName = params.organizationName
  def appGitRepoName = params.appGitRepoName

  print params

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def userInput

    currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Release?')
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }
    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      deploy(gitBranchName, buildNumber,
             releaseVersion, organizationName, appGitRepoName,
             gitScm, gitCommit, crNumber)
    }
  }
}

def deploy(gitBranchName, buildNumber,
       releaseVersion, organizationName, appGitRepoName,
       gitScm, gitCommit, crNumber) {

  def commons = new com.westernasset.pipeline.Commons()
  def app = new com.westernasset.pipeline.util.AwsAppUtil()
  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'cdk', image: "${env.TOOL_CDK}", ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
      containerTemplate(name: 'tf', image: "${env.TOOL_TF}", ttyEnabled: true),
      containerTemplate(name: 'tg', image: "${env.TOOL_TG}", ttyEnabled: true),
      containerTemplate(name: 'sam', image: "${env.TOOL_SAM}", ttyEnabled: true),
      containerTemplate(name: 'eksctl', image: "${env.TOOL_EKSCTL}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ])  {
    node(POD_LABEL) {
      try {
        currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
        echo currentBuild.displayName

        stage('Clone') {
          deleteDir()
          git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
          sh "git reset --hard ${gitCommit}"
        }

        def gitReleaseTagName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
        sh """
          git config --global user.email "jenkins@westernasset.com"
          git config --global user.name "Jenkins Agent"
          git config --global http.sslVerify false
          git config --global push.default matching
          git config -l

          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}"'
          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
        """

        def gitRepo = [
          gitCommit: gitCommit,
          gitScm: gitScm,
          organizationName: organizationName,
          appGitRepoName: appGitRepoName,
          appDtrRepo: organizationName + '/' + appGitRepoName
        ]

        yaml = readYaml file: "jenkins.yaml"
        //echo sh(script: 'env|sort', returnStdout: true)
        println yaml
        stage("Prod Deployment") {
          for (component in yaml.components) {
            if (component.enabled) {
               app.components(component, component.type,  "prod", gitRepo)
               app.components(component, component.type, "sharedservice", gitRepo)
            }
          }
        }
      } catch(err) {
        println err
      }
    }
  }
}
