package com.aristotlecap.pipeline.operationRelease

import com.aristotlecap.pipeline.util.ConfluentUtil
import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*
import com.aristotlecap.pipeline.*

def build(params) {
  def didTimeout

  def buildNumber = params.buildNumber
  def crNumber = params.crNumber
  def gitBranchName = params.gitBranchName
  GitRepository repo = GitRepository.fromJsonString(params.repo)
  def releaseVersion = params.releaseVersion
  def builderTag = params.builderTag
  def schemaPathsString = params.schemaPaths
  def mavenBuild = new MavenBuild()
  def conditionals = new Conditionals()
  GitScm gitScm = new GitScm()

  print params

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

  Prompt prompt = new Prompt()
  Commons commons = new Commons()

  if (!prompt.approve(message: 'Approve Release?')) {
      return // Do not proceed if there is no approval
  }

  conditionals.lockWithLabel {
    def maven = new Maven()
    def pod = new PodTemplate()
    def ssh = new Ssh()
    def git = new Git()
    def ccloud = new CCloud()
    def jnlp = new Jnlp()

    def buildImage = BuilderImage.fromTag(env, builderTag)

    print buildImage.image

    pod.node(
      containers: [ maven.containerTemplate(buildImage.image), ccloud.containerTemplate(), jnlp.containerTemplate() ],
      volumes: [ maven.cacheVolume(), ssh.keysVolume() ]
    ) {
      stage('Clone') {
        deleteDir()
        gitScm.checkout(repo)
      }
      stage('Prod Deployment') {
        currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
        maven.container {
          sh """
            export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
            mvn clean install
          """
        }


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
      }
    }
  }

}
