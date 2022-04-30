package com.westernasset.pipeline;

def build(projectType, gitBranchName, buildNumber, builderTag, jobIdsNonprod,
          jobIdsProd, deleteNonprodJobIds, deleteProdJobIds, releaseVersion) {

  def repo
  podTemplate(
    cloud: 'pas-development',
    containers: [
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins') {
    node(POD_LABEL) {
      try {
        def commons = new com.westernasset.pipeline.Commons()
        repo = commons.clone()
        currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  promoteToProduction(projectType, repo.appGitRepoName, gitBranchName, buildNumber, builderTag,
                      jobIdsNonprod, jobIdsProd, deleteNonprodJobIds, deleteProdJobIds, releaseVersion,
                      repo.gitCommit, repo.gitScm)
}

def promoteToProduction(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag,
                        jobIdsNonprod, jobIdsProd, deleteNonprodJobIds, deleteProdJobIds, releaseVersion,
                        gitCommit, gitScm) {

  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
  def gate = gateutil.gate(null, false, true, false, currentBuild.displayName, 'Ready to Release?', 'Approve Release?')
  if (gate.crNumber != null) {
    podTemplate(
      cloud: 'pas-development',
      serviceAccount: 'jenkins',
      namespace: 'devops-jenkins',
      containers: [
        containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}')
      ],
      volumes: [
          persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
    ]) {
      node(POD_LABEL) {
        try {
          currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${gate.crNumber}"
          def displayTag = currentBuild.displayName

          // Clean workspace before doing anything
          deleteDir()
          git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
          sh "git reset --hard ${gitCommit}"

          def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}"
          sh """
            git config --global user.email "jenkins@westernasset.com"
            git config --global user.name "Jenkins Agent"
            git config --global http.sslVerify false
            git config --global push.default matching
            git config -l

            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gate.crNumber}" '
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
          """
          stage('Trigger Downstream Job') {
            build job: "${env.opsReleaseJob}", wait: false, parameters: [
              [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectType)],
              [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
              [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
              [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
              [$class: 'StringParameterValue', name: 'jobIdsNonprod', value: String.valueOf(jobIdsNonprod)],
              [$class: 'StringParameterValue', name: 'jobIdsProd', value: String.valueOf(jobIdsProd)],
              [$class: 'StringParameterValue', name: 'deleteNonprodJobIds', value: String.valueOf(deleteNonprodJobIds)],
              [$class: 'StringParameterValue', name: 'deleteProdJobIds', value: String.valueOf(deleteProdJobIds)],
              [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)],
              [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
              [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
              [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(gate.crNumber)],
              [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)]
            ]
          }
        } catch (err) {
          currentBuild.result = 'FAILED'
          throw err
        }
      }
    }
  }
}
