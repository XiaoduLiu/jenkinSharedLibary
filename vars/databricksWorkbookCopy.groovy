#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def build = new com.aristotlecap.pipeline.databricksWorkbookCopyBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        'databricksWorkbookCopy',
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${config.appPath}",
        "${config.module}",
        "${config.fromEnv}",
        "${config.toEnv}",
        "${config.releaseVersion}")
    }
  } else {
    build.build(
      'databricksWorkbookCopy',
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${config.appPath}",
      "${config.module}",
      "${config.fromEnv}",
      "${config.toEnv}",
      "${config.releaseVersion}")
  }
}
