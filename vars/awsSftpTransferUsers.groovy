#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  config.build_number = env.BUILD_NUMBER

  params.each{ key, value ->
    config[key] = value;
  }

  println config

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def build = new com.aristotlecap.pipeline.awsSftpTransferUsers()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.call(config)
    }
  } else {
    build.call(config)
  }

}
