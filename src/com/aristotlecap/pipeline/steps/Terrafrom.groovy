package com.aristotlecap.pipeline.steps

import com.aristotlecap.pipeline.models.*

def container(Closure body) {
    container('tf') {
        return body()
    }
}

def containerTemplate(String image = env.TOOL_TF) {
    return containerTemplate(name: 'tf', image: image, ttyEnabled: true)
}

def azurePlan(component) {
  if (component.enabled) {
    def wk = component.resource
    container('tf') {
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
            terraform --version
            ls -la
            terraform init
            terraform plan -no-color
           """
        }
      }
    }
  }
}

def azureApply(component) {
  if (component.enabled) {
    def wk = component.resource
    container('tf') {
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
            terraform --version
            ls -la
            terraform init
            terraform plan -no-color
            terraform apply -auto-approve -parallelism=256 -no-color
           """
        }
      }
    }
  }
}
