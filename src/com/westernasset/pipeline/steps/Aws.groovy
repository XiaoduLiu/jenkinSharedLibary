package com.westernasset.pipeline.steps

import com.westernasset.pipeline.models.*

def container(Closure body) {
    container('aws') {
        return body()
    }
}

def containerTemplate(String image = env.TOOL_AWS) {
    return containerTemplate(name: 'aws', image: image, ttyEnabled: true)
}

def awsVolume() {
    return persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
}

def prodAwsVolume() {
    return persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws')
}

def getDevopsBucket(account) {
  def map = [ 'aws-sandbox': 'wa-devops-s', 'aws-nonprod': 'wa-devops-n', 'aws-prod': 'wa-devops-x', 'aws-sandbox-devops': 'wa-devops-sd']
  return map[account]
}

def getProfile(account) {
  def map = [ 'aws-sandbox': 'sandbox', 'aws-nonprod': 'nonprod', 'aws-prod': 'prod', 'aws-sandbox-devops': 'sandbox-devops']
  return map[account]
}

def getEksCluster(account) {
  def map = [ 'aws-sandbox': 'us-west-2-eks-sandbox', 'aws-nonprod': 'us-west-2-eks-development', 'aws-prod': 'us-west-2-eks-production', 'aws-sandbox-devops': 'us-west-2-eks-sandbox-devops']
  return map[account]
}
