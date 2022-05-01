#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def build = new com.westernasset.pipeline.dockerImagesBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(config)
    }
  } else {
    build.build(config)
  }
}