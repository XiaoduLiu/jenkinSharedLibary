#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  config.branch_name = env.BRANCH_NAME
  config.build_number = env.BUILD_NUMBER

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  if (config.dashboardDeploymentOnly == null) {
    config.dashboardDeploymentOnly = false
  }

  def build = new com.aristotlecap.pipeline.monitoring()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.call(config)
    }
  } else {
    build.call(config)
  }
}
