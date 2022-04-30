package com.westernasset.pipeline.operationRelease

import com.westernasset.pipeline.util.ConfluentUtil
import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.builds.*
import com.westernasset.pipeline.*

def build(params) {
  def didTimeout

  def buildNumber = params.buildNumber
  def crNumber = params.crNumber
  def gitBranchName = params.gitBranchName
  def gitScm = params.gitScm
  def builderTag = params.builderTag
  def ccloudBuild = new KafkaBuild()
  def conditionals = new Conditionals()
  GitRepository repo = GitRepository.fromJsonString(params.repo)

  println params

  currentBuild.displayName = "${gitBranchName}-prod-deploy-${buildNumber}-${crNumber}"

  Prompt prompt = new Prompt()
  Commons commons = new Commons()

  if (!prompt.approve(message: 'Approve Release?')) {
      return // Do not proceed if there is no approval
  }

  conditionals.lockWithLabel {
    ccloudBuild.deploy(builderTag, gitScm, buildNumber, gitBranchName, 'prod', true, repo, crNumber, false)
  }

}
