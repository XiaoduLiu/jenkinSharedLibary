package com.westernasset.pipeline.operationRelease

import com.westernasset.pipeline.util.ConfluentUtil
import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.builds.*
import com.westernasset.pipeline.*

def build(params) {
  def didTimeout

  def buildNumber = params.buildNumber
  def crNumber = params.crNumber
  def gitBranchName = params.gitBranchName
  GitRepository repo = GitRepository.fromJsonString(params.repo)
  def builderTag = params.builderTag
  def schemaPathsString = params.schemaPaths
  def conditionals = new Conditionals()
  GitScm gitScm = new GitScm()

  currentBuild.displayName = "${gitBranchName}-${buildNumber}-${crNumber}"

  Prompt prompt = new Prompt()
  Commons commons = new Commons()

  if (!prompt.approve(message: 'Approve Release?')) {
      return // Do not proceed if there is no approval
  }

  conditionals.lockWithLabel {
    def pod = new PodTemplate()
    def ssh = new Ssh()
    def git = new Git()
    def ccloud = new CCloud()
    def jnlp = new Jnlp()

    def buildImage = BuilderImage.fromTag(env, builderTag)

    print buildImage.image

    pod.node(
      containers: [ ccloud.containerTemplate(), jnlp.containerTemplate() ],
      volumes: [ ssh.keysVolume() ]
    ) {
      stage('Clone') {
        deleteDir()
        gitScm.checkout(repo)
      }
      stage('Prod Deployment') {
        currentBuild.displayName = "${gitBranchName}-${buildNumber}-${crNumber}"

        println schemaPathsString
        def sp = []
        for(path in schemaPathsString.split('::')) {
          print 'YYYY->' + path
          sp.add(path)
        }
        for(s in sp) {
          print "XXXX->" + s
        }

        ccloud.ccloudLogin()
        ccloud.uploadSchema(sp, 'prod')

        def gitReleaseTagName = "${gitBranchName}-${buildNumber}-${crNumber}"
        sh """
          git config --global user.email "jenkins@westernasset.com"
          git config --global user.name "Jenkins Agent"
          git config --global http.sslVerify false
          git config --global push.default matching
          git config -l

          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}"'
          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
        """
      }
    }
  }

}
