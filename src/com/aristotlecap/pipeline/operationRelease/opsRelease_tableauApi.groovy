package com.aristotlecap.pipeline.operationRelease

import com.aristotlecap.pipeline.Commons
import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*
import net.sf.json.JSONObject

def build(Map param) {

  println('working here inside the XXXXXX')
  println param
  def projectType = params.projectType

  def repo = new GitRepository(
               param.appGitRepoName,
               param.organizationName,
               param.gitScm,
               param.gitCommit,
               param.gitBranchName,
               param.appDtrRepo)
  def prompt = new Prompt()
  def deployment = new ProductionDeployment(param.gitBranchName, param.buildNumber,
      projectType, repo, param.crNumber, null, null, param.releaseVersion)
  def tableauBuild = new TableauApiBuild()

  def approved = prompt.productionDeploy(deployment)

  if (!approved) {
    echo 'Production deploy not approved'
    currentBuild.result = 'SUCCESS'
    return
  }

  def conditionals = new Conditionals()
  conditionals.lockWithLabel {
    tableauBuild.build(param, true)
  }
}
