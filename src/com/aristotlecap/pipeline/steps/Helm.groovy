package com.aristotlecap.pipeline.steps

import com.aristotlecap.pipeline.models.*

def container(Closure body) {
    container('helm') {
        return body()
    }
}

def containerTemplate(String image = env.TOOL_HELM) {
    return containerTemplate(name: 'helm', image: image, ttyEnabled: true)
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
