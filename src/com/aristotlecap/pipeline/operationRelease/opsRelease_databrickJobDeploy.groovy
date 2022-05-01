package com.aristotlecap.pipeline.operationRelease;

def build(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag,
          jobIdsNonprod, jobIdsProd, deleteNonprodJobIds, deleteProdJobIds, releaseVersion,
          gitCommit, gitScm, crNumber) {

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
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
      databricksWorkbookCopyImpl(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag,
                                 jobIdsNonprod, jobIdsProd, deleteNonprodJobIds, deleteProdJobIds, releaseVersion,
                                 gitCommit, gitScm, crNumber)
    }
  }
}

def databricksWorkbookCopyImpl(projectType, appGitRepoName, gitBranchName, buildNumber, builderTag,
                               jobIdsNonprod, jobIdsProd, deleteNonprodJobIds, deleteProdJobIds, releaseVersion,
                               gitCommit, gitScm, crNumber) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def databrickImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/devops/jenkins-builder:${builderTag}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'databrick', image: "${databrickImage}", ttyEnabled: true)],
    volumes: [
        persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
    ]) {
    node(POD_LABEL) {
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        echo workspace

        container('databrick') {
          if (jobIdsNonprod != 'null') {
            def jobIdsNonprodMap = commons.getMapFromString(jobIdsNonprod)
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.DATABRICK_JOB_NONPROD}",
              usernameVariable: 'DATABRICKS_HOST', passwordVariable: 'DATABRICKS_TOKEN']]) {
              deleteDatabricksJobs(deleteNonprodJobIds)
              jobIdsNonprodMap.each{ k, v ->
                println "${k}:${v}"
                if (v == 'null') {
                  sh """
                    databricks jobs create --json-file $workspace/$k
                  """
                } else {
                  sh """
                    databricks jobs reset --job-id $v --json-file $workspace/$k
                  """
                }
              }
            }
          }
          if (jobIdsProd != 'null') {
            def jobIdsProdMap = commons.getMapFromString(jobIdsProd)
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.DATABRICK_JOB_PROD}",
              usernameVariable: 'DATABRICKS_HOST', passwordVariable: 'DATABRICKS_TOKEN']]) {
              deleteDatabricksJobs(deleteProdJobIds)
              jobIdsProdMap.each{ k, v ->
                println "${k}:${v}"
                if (v == 'null') {
                  sh """
                    databricks jobs create --json-file $workspace/$k
                  """
                } else {
                  sh """
                    databricks jobs reset --job-id $v --json-file $workspace/$k
                  """
                }
              }
            }
          }
        }

      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
}

def deleteDatabricksJobs(deleteJobIds) {
  if (deleteJobIds != 'null') {
    def fa = deleteJobIds.split()
    def dem = fa.length
    def i = 0
    while (i < dem) {
      echo "i=" + i
      def job = fa[i]
      echo "delete secret job -> " + job
      sh """
        databricks jobs delete --job-id ${JOB_ID}r
      """
      i=i+1
    }
  }
}
