#!/usr/bin/groovy

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.builds.*

def validate(def config) {
  def errors = []
  if (!config.configFile) {
    errors.add('Missing required field: azureTG.configFile')
  }
  if (errors.size() > 0) {
    error errors.join('\n')
  }
}

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  validate(config)

  def conditionals = new Conditionals()
  def azureBuild = new AzureBuild()
  def prompt = new Prompt()
  def repo
  def appVersoin
  def displayLabel

  conditionals.lockWithLabel {
    azureBuild.build(config)
  }

  prompt.proceed()

  conditionals.lockWithLabel {
    azureBuild.deploy(config)
  }

}
