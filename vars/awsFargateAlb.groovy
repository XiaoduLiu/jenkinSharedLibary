#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def build = new com.aristotlecap.pipeline.awsFargateAlb()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "awsFargateAlb",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        config.buildSteps,
        config.nonProdEnvs,
        config.qaEnvs,
        config.releaseVersion,
        config.budgetCode
      )
    }
  } else {
    build.build(
      "awsFargateAlb",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      config.builderTag,
      config.buildSteps,
      config.nonProdEnvs,
      config.qaEnvs,
      config.releaseVersion,
      config.budgetCode
    )
  }
}
