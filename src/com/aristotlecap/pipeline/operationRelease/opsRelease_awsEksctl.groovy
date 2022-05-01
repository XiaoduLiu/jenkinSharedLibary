package com.aristotlecap.pipeline.operationRelease

import com.aristotlecap.pipeline.Commons
import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*
import net.sf.json.JSONObject

def build(Map param) {

  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def jnlp = new Jnlp()
  def eksctl = new AwsEksctl()
  def git = new Git()
  def ssh = new Ssh()
  def kubectl = new Kubectl()
  def aws = new Aws()
  def awsEksctlBuild = new AwsEksctlBuild()
  def display = new DisplayLabel()

  def projectType = params.projectType
  def configStr = params.configString

  def p = readJSON text: configStr
  print p

  def repo = new GitRepository(
               p.name,
               p.organization,
               p.scm,
               p.commit,
               p.branch,
               null)

  def prompt = new Prompt()
  def deployment = new ProductionDeployment(p.branch, p.buildNumber,
      projectType, repo, p.crNumber, null, null, p.releaseVersion)

  def approved = prompt.productionDeploy(deployment)

  if (!approved) {
    echo 'Production deploy not approved'
    currentBuild.result = 'SUCCESS'
    return
  }

  BuilderImage buildImage = BuilderImage.fromTag(env, p.builderTag)

  pod.node(
    cloud: 'sc-production',
    containers: [
      jnlp.containerTemplate(),
      eksctl.containerTemplate(buildImage),
      kubectl.containerTemplate()],
    volumes: [
      eksctl.awsProdVolume(),
      ssh.keysVolume()]
  ) {
    stage('Clone') {
      gitScm.checkout(repo, 'ghe-jenkins')
    }
    stage('Non Prod Deployment') {
      def releaseDetails = ''
      def needTag = false
      eksctl.container {
        def p1 = awsEksctlBuild.processScripts(p.prodEksScripts, true, p.prodProfile)
        if (p1.needTag) {
          needTag = p1.needTag
        }
        releaseDetails = releaseDetails + '-' + p1.releaseDetails

      }
      kubectl.container {
        def p2 = awsEksctlBuild.processScripts(p.prodKubeScripts, false, null)
        if (p2.needTag) {
          needTag = p2.needTag
        }
        releaseDetails = releaseDetails + '-' + p2.releaseDetails
      }

      if (needTag) {
       deleteDir()
        gitScm.checkout(repo, 'ghe-jenkins');
        def gitReleaseTag = "${repo.safeName}-${p.prodProfile}-release-${p.releaseVersion}"
        git.useJenkinsUser()
        sh "git tag -a $gitReleaseTag -m \"Release for ${releaseDetails}\""
        sh "git push origin $gitReleaseTag"
      }
    }
  }
}
