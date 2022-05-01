#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def commons = new com.aristotlecap.pipeline.Commons()

  def build = new com.aristotlecap.pipeline.npmModuleBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "npmModule",
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        "${config.builderTag}",
        "${config.libraryType}",
        config.angularLibs
      )
    }
  } else {
    build.build(
      "npmModule",
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      "${config.builderTag}",
      "${config.libraryType}",
       config.angularLibs
    )
  }
}
