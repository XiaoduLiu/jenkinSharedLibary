package com.aristotlecap.pipeline.steps

import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import com.aristotlecap.pipeline.models.ProductionDeployment

def prompt(def defaults, Closure body) {
  try {
    return body()
  } catch(FlowInterruptedException err) {
    currentBuild.result = 'SUCCESS'
    def user = err.getCauses()[0].getUser()
    if('SYSTEM' == user.toString()) { // SYSTEM means timeout
      println "Timeout ..."
    } else {
      print "Aborted by [${user}]"
    }
    return defaults
  }
}

def nonProdWithDeployOption(List<String> environments, List<String> prodPrereqEnvironments = [], Map<String, Boolean> appSelectionMap) {
  String stageName = 'Non-production deploy?'
  String message = 'Select non-production environment target'
  String description = !prodPrereqEnvironments.isEmpty() ?
    "${String.join(', ', prodPrereqEnvironments)} for pre-production deployments" :
    "Environments"

  def params = []
  params.add([$class: 'ChoiceParameterDefinition', choices: environments, description: description, name: 'environment'])
  appSelectionMap.each { key, val ->
    params.add([$class: 'BooleanParameterDefinition', defaultValue: val, description: "deploy ${key}", name: key])
  }
  stage(stageName) {
    checkpoint(stageName)

    return prompt('') {
      timeout(time: 60, unit: 'SECONDS') {
        return input(id: 'Proceed', message: message, parameters: params)
      }
    }
  }
}

def nonprod(List<String> environments, List<String> prodPrereqEnvironments = []) {
  String stageName = 'Non-production deploy?'
  String message = 'Select non-production environment target'
  String description = !prodPrereqEnvironments.isEmpty() ?
    "${String.join(', ', prodPrereqEnvironments)} for pre-production deployments" :
    "Environments"

  stage(stageName) {
    checkpoint(stageName)

    return prompt('') {
      timeout(time: 60, unit: 'SECONDS') {
        return input(id: 'Proceed', message: message, parameters: [
          [$class: 'ChoiceParameterDefinition', choices: environments, description: description, name: 'environment']
        ])
      }
    }
  }
}

def nonprodNoQA(List<String> environments) {
  String stageName = 'Non-production deploy?'
  String message = 'Select non-production environment target'

  stage(stageName) {
    checkpoint(stageName)

    return prompt('') {
      timeout(time: 60, unit: 'SECONDS') {
        return input(id: 'Proceed', message: message, parameters: [
          [$class: 'ChoiceParameterDefinition', choices: environments, description: 'Environments', name: 'environment']
        ])
      }
    }
  }
}

def changeRequest() {
  String stageName = 'Approve production deploy'

  stage(stageName) {
    checkpoint(stageName)

    return prompt('') {
      timeout(time: 60, unit: 'SECONDS') {
        return input(id: 'Proceed', message: "Enter change request number to procced with production deployment", parameters: [
          [$class: 'TextParameterDefinition', defaultValue: '', description: 'Change Request number', name: 'CR_Number']
        ])
      }
    }
  }
}

def productionDeploy(ProductionDeployment productionDeployment) {
  String stageName = 'Should I deploy to PROD?'
  String checkpointName = 'Deploy to Production'
  def branchName = (productionDeployment.branchName != null)? productionDeployment.branchName+"-":""
  def buildNumber = (productionDeployment.buildNumber != null)? productionDeployment.buildNumber+"-":""

  currentBuild.displayName = "${branchName}${buildNumber}${productionDeployment.releaseVersion}-${productionDeployment.crNumber}"

  stage(stageName) {
    checkpoint(checkpointName)

    return prompt(false) {
      timeout(time: 60, unit: 'SECONDS') {
        // Check for the inverse of false because input returns null if Proceed and false if Abort
        // so  !(null == false) -> true
        // and !(false == false) -> false
        return !(input(id: 'productionDeploy', message: 'Approve Release?') == false)
      }
    }
  }
}

def release() {
  stage('Release Checkpoint') {
    checkpoint 'Release checkpoint'
    return prompt(false) {
      timeout(time: 60, unit: 'SECONDS') {
        return !(input(id: 'release', message: 'Approve Release?') == false)
      }
    }
  }
}

def releaseNonProd() {
  stage('Release NonProd Checkpoint') {
    checkpoint 'Release NonProd checkpoint'
    return prompt(false) {
      timeout(time: 60, unit: 'SECONDS') {
        return !(input(id: 'release', message: 'Approve NonProd Release?') == false)
      }
    }
  }
}

Boolean approve(Map args) {
  stage('Should I deploy to PROD?') {
    checkpoint 'Deploy To Prod'
    String message = args.message ?: 'Approve?'
    Integer time = args.time ?: 60
    String unit = args.unit ?: 'SECONDS'
    return prompt(false) {
      timeout(time: time, unit: unit) {
        return !(input(id: 'approve', message: message) == false)
      }
    }
  }
}

Boolean proceed() {
  stage('Ready to Deploy?') {
    checkpoint 'Ready to Deploy'
    String message = 'Approve?'
    Integer time = 60
    String unit = 'SECONDS'
    return prompt(false) {
      timeout(time: time, unit: unit) {
        return !(input(id: 'Proceed', message: message) == false)
      }
    }
  }
}

def nonprodWithSkip(List<String> environments, List<String> prodPrereqEnvironments = []) {
  String stageName = 'Non-production deploy?'
  String message = 'Select non-production environment target'
  String description = !prodPrereqEnvironments.isEmpty() ?
    "${String.join(', ', prodPrereqEnvironments)} for pre-production deployments" :
    "Environments"

  stage(stageName) {
    checkpoint(stageName)

    return prompt('') {
      timeout(time: 60, unit: 'SECONDS') {
        return input(id: 'Proceed', message: message, parameters: [
          [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Skip Non-production Deployment?', name: 'skipNonprod'],
          [$class: 'ChoiceParameterDefinition', choices: environments, description: description, name: 'environment']
        ])
      }
    }
  }
}

return this
