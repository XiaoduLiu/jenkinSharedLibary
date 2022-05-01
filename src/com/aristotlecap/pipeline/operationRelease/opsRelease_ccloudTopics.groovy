package com.aristotlecap.pipeline.operationRelease

import com.aristotlecap.pipeline.util.ConfluentUtil
import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*
import com.aristotlecap.pipeline.*

def build(params) {
  def didTimeout

  def buildNumber = params.buildNumber
  def crNumber = params.crNumber
  def gitBranchName = params.gitBranchName
  def gitScm = params.gitScm
  def builderTag = params.builderTag
  def ccloudBuild = new CCloudTopicBuild()
  def conditionals = new Conditionals()

  currentBuild.displayName = "${gitBranchName}-prod-deploy-${buildNumber}-${crNumber}"

  Prompt prompt = new Prompt()
  Commons commons = new Commons()

  if (!prompt.approve(message: 'Approve Release?')) {
      return // Do not proceed if there is no approval
  }

  conditionals.lockWithLabel {
    ccloudBuild.deploy(builderTag, gitScm, buildNumber, gitBranchName, 'prod')
  }

}
