#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def build = new com.aristotlecap.pipeline.awsAppBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build("awsApp",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.releaseVersion}")
    }
  } else {
    build.build("awsApp",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.releaseVersion}")
  }
}
