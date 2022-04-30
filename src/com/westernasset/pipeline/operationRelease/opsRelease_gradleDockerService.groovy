package com.westernasset.pipeline.operationRelease

import com.westernasset.pipeline.Commons
import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import net.sf.json.JSONObject

def build(Map params) {
    def repo = new GitRepository(params.appGitRepoName, params.organizationName, params.gitScm, params.gitCommit, params.gitBranchName, params.appDtrRepo)
    def releaseDockerImage = new ReleaseDockerImage(repo, env, params.releaseVersion)
    def deployment = new KubernetesProductionDeployment(
        params.projectType, repo, params.crNumber, params.prodEnv, params.drEnv, params.releaseVersion, releaseDockerImage, params.templates ? JSONObject.fromObject(params.templates) : null)
    build(deployment)
}

def build(KubernetesProductionDeployment deployment) {
  def prompt = new Prompt()

  def approved = prompt.productionDeploy(deployment)

  if (!approved) {
    echo 'Production deploy not approved'
    currentBuild.result = 'SUCCESS'
    return
  }

  def commons = new Commons()
  def pod = new PodTemplate()
  def kubectl = new Kubectl()
  def gitScm = new GitScm()
  def vault = new Vault()
  def conditionals = new Conditionals()

  currentBuild.displayName = deployment.releaseVersion + '-' + deployment.crNumber + '-' + deployment.prodEnv

  def deployDR = pod.node(
    cloud: commons.getProdCluster(deployment.prodEnv),
    containers: [
      containerTemplate(name: 'jnlp', image: env.TOOL_AGENT, args: '${computer.jnlpmac} ${computer.name}'),
      kubectl.containerTemplate(),
      vault.containerTemplate()
    ]
  ) {

    // def workspace = commons.getWorkspace()
    gitScm.checkout(deployment.repo, 'ghe-jenkins')

    stage('Generate Secrets') {
      conditionals.when(deployment.templates != null) {
        def secrets

        vault.container {
          secrets = vault.processTemplates(deployment.repo, 'prod', deployment.templates)
        }

        kubectl.container {
          kubectl.createSecret(deployment.repo, 'prod', secrets)
        }
      }
    }

    stage('Deploy to Production') {
      kubectl.deploy('prod', deployment.repo, deployment.image)
    }

    if (!fileExists("conf/env/dr.groovy")) {
      return false
    }
  }

  if (deployDR == false) {
    return
  }

  pod.node(
    cloud: commons.getProdCluster(deployment.drEnv),
    containers: [
      containerTemplate(name: 'jnlp', image: env.TOOL_AGENT, args: '${computer.jnlpmac} ${computer.name}'),
      kubectl.containerTemplate(),
      vault.containerTemplate()
    ]
  ) {

    // def workspace = commons.getWorkspace()
    gitScm.checkout(deployment.repo, 'ghe-jenkins')

    stage('Deploy to DR') {
      kubectl.deploy('dr', deployment.repo, deployment.image)
    }
  }
}
