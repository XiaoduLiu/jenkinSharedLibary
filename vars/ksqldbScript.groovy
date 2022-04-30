#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  config.branch_name = env.BRANCH_NAME
  config.build_number = env.BUILD_NUMBER

  println config

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def build = new com.westernasset.pipeline.ksqldbScript()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.call(config)
    }
  } else {
    build.call(config)
  }

}
