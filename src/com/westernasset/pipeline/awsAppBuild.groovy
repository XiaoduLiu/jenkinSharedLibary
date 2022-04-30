package com.westernasset.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, releaseVersion) {

  def yaml
  def commons = new com.westernasset.pipeline.Commons()
  def app = new com.westernasset.pipeline.util.AwsAppUtil()
  def gitRepo
  podTemplate(
    cloud: 'pas-development',
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
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws'),
      persistentVolumeClaim(claimName: 'jenkins-npm-cache', mountPath: '/home/jenkins/.npm')
  ]) {
    node(POD_LABEL) {
      // Clean workspace before doing anything
      deleteDir()
      gitRepo = commons.clone()
      println gitRepo
      yaml = readYaml file: "jenkins.yaml"

      currentBuild.displayName  = "${gitBranchName}-${releaseVersion}-${buildNumber}"

      //echo sh(script: 'env|sort', returnStdout: true)
      println yaml

      stage("NonProd Deployment") {
        for (component in yaml.components) {
          if (component.enabled) {
             app.components(component, component.type,  "sandbox", gitRepo)
             app.components(component, component.type,  "nonprod", gitRepo)
             app.components(component, component.type, "sandbox-devops", gitRepo)
          }
        }
      }
      deleteDir()
    }
  }
  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, true, false, currentBuild.displayName, 'Ready to Release?', 'Approve Release?')
  if (gate.crNumber != null) {
    qaApprove(gitBranchName, buildNumber, releaseVersion, gate.crNumber,
              gitRepo.gitCommit, gitRepo.gitScm, gitRepo.organizationName, gitRepo.appGitRepoName)
  }
}

def qaApprove(gitBranchName, buildNumber, releaseVersion, crNumber,
              gitCommit, gitScm, organizationName, appGitRepoName) {
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
  stage('trigger downstream job') {
    build job: "${env.opsReleaseJob}", wait:false, parameters: [
      [$class: 'StringParameterValue', name: 'projectType', value: 'awsApp'],
      [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
      [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(crNumber)],
      [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
      [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
      [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
      [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
      [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
      [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)]
    ]
  }
}
