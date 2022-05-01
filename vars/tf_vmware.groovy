#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def org = params.organizationName
  def repo = params.repoName
  def resource = params.resourceName
  def tag = config.builderTag

  echo org
  echo repo
  echo resource
  echo "builderTag = ${config.builderTag}"

  def build = new com.aristotlecap.pipeline.terraform.vmware()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        tag,
        org,
        repo,
        resource
      )
    }
  } else {
    build.build(
      tag,
      org,
      repo,
      resource
    )
  }
}
