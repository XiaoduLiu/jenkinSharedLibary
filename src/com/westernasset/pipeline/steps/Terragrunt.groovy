package com.westernasset.pipeline.steps

import com.westernasset.pipeline.models.*

def container(Closure body) {
    container('tg') {
        return body()
    }
}

def containerTemplate(String image = env.TOOL_TG) {
    return containerTemplate(name: 'tg', image: image, ttyEnabled: true)
}

def awsVolume() {
    return persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
}

def getDevopsBucket(account) {
  def map = [ 'aws-sandbox': 'wa-devops-s', 'aws-nonprod': 'wa-devops-n', 'aws-prod': 'wa-devops-x', 'aws-sandbox-devops': 'wa-devops-sd']
  return map[account]
}

def getProfile(account) {
  def map = [ 'aws-sandbox': 'sandbox', 'aws-nonprod': 'nonprod', 'aws-prod': 'prod', 'aws-sandbox-devops': 'sandbox-devops']
  return map[account]
}

def azurePlan(component) {
  if (component.enabled) {
    def wk = component.resource
    container('tg') {
      withCredentials([[$class: 'UsernamePasswordMultiBinding',
                         credentialsId: "${env.AZURE_SECRET}",
                         usernameVariable: 'NOTHING',
                         passwordVariable: 'ARM_CLIENT_SECRET']]) {
        withEnv(["ARM_USE_MSI=${env.ARM_USE_MSI}",
                 "ARM_TENANT_ID=${env.ARM_TENANT_ID}",
                 "ARM_CLIENT_ID=${env.ARM_CLIENT_ID}"]) {
          sh """
            cd $wk
            pwd
            terragrunt --version
            ls -la
            terragrunt init
            terragrunt plan -no-color
           """
        }
      }
    }
  }
}

def azureApply(component) {
  if (component.enabled) {
    def wk = component.resource
    container('tg') {
      withCredentials([[$class: 'UsernamePasswordMultiBinding',
                         credentialsId: "${env.AZURE_SECRET}",
                         usernameVariable: 'NOTHING',
                         passwordVariable: 'ARM_CLIENT_SECRET']]) {
        withEnv(["ARM_USE_MSI=${env.ARM_USE_MSI}",
                 "ARM_TENANT_ID=${env.ARM_TENANT_ID}",
                 "ARM_CLIENT_ID=${env.ARM_CLIENT_ID}"]) {
          sh """
            cd $wk
            pwd
            terragrunt --version
            ls -la
            terragrunt init
            terragrunt plan -no-color
            terragrunt apply -auto-approve -parallelism=256 -no-color
           """
        }
      }
    }
  }
}
