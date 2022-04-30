#!/usr/bin/groovy

import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  if (config.dockerFiles == null) {
    error "Required field 'dockerFiles' not set"
  } else if (config.imageTags == null) {
    error "Required field 'imageTags' not set"
  } else if (!(config.dockerFiles instanceof List)) {
    error "Field 'dockerFiles' is not a list"
  } else if (!(config.imageTags instanceof List)) {
    error "Field 'imageTags' is not a list"
  } else if (config.dockerFiles.size() != config.imageTags.size()) {
    error "Fields 'dockerFiles' and 'imageTags' need to have the same number of elements"
  }

  def dockerFileImageMap = [:]

  config.dockerFiles.eachWithIndex { dockerFile, i ->
    dockerFileImageMap.put(dockerFile, config.imageTags.get(i))
  }

  def templatesArray = config.templates
  def templatesString = "null"
  if (templatesArray != null && templatesArray.size() > 0) {
     templatesString = templatesArray.join("\n")
  }

  def secretsArray = config.secrets
  def secretsString = "null"
  if (secretsArray != null && secretsArray.size() > 0) {
     secretsString = secretsArray.join("\n")
  }

  Boolean preserveRootContext = config.preserveRootContext ?: false

  def build = new com.westernasset.pipeline.dockerImagesBuild()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.build(
        "${env.BRANCH_NAME}",
        "${env.BUILD_NUMBER}",
        dockerFileImageMap,
        "${templatesString}",
        "${secretsString}",
        preserveRootContext
      )
    }
  } else {
    build.build(
      "${env.BRANCH_NAME}",
      "${env.BUILD_NUMBER}",
      dockerFileImageMap,
      "${templatesString}",
      "${secretsString}",
      preserveRootContext
    )
  }
}
