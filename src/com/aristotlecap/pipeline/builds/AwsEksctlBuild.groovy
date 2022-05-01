package com.aristotlecap.pipeline.builds

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.build.*


def nonProdBuild(config) {
    def repo
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def jnlp = new Jnlp()
    def eksctl = new AwsEksctl()
    def git = new Git()
    def ssh = new Ssh()
    def kubectl = new Kubectl()
    def aws = new Aws()
    def display = new DisplayLabel()

    print config
    print env

    BuilderImage buildImage = BuilderImage.fromTag(env, config.builderTag)
  
    print config

    pod.node(
      containers: [
        jnlp.containerTemplate(),
        eksctl.containerTemplate(buildImage),
        kubectl.containerTemplate()],
      volumes: [
        eksctl.awsNonProdVolume(),
        ssh.keysVolume()]
      ) {
        stage('Clone') {
            repo = gitScm.checkout()
        }
        stage('Non Prod Deployment') {
            def releaseDetails = ''
            def needTag = false
            eksctl.container {
              def p1 = processScripts(config.nonProdEksScripts, true, config.nonprodProfile)
              if (p1.needTag) {
                needTag = p1.needTag
              }
              releaseDetails = releaseDetails + '-' + p1.releaseDetails
              
            }
            kubectl.container {
              def p2 = processScripts(config.nonProdKubeScripts, false, null)
              if (p2.needTag) {
                needTag = p2.needTag
              }
              releaseDetails = releaseDetails + '-' + p2.releaseDetails
            }

            if (needTag) {
              deleteDir()
              gitScm.checkout(repo, 'ghe-jenkins');
              def gitReleaseTag = "${repo.safeName}-${config.nonprodProfile}-release-${config.releaseVersion}"
              git.useJenkinsUser()
              sh "git tag -a $gitReleaseTag -m \"Release for ${releaseDetails}\""
              sh "git push origin $gitReleaseTag"
            }
        }
      }
      return [repo]
}

def processScripts(scripts, needProfile, profile) {
  print scripts
  print scripts!=null
  print scripts.class.name
  print (scripts instanceof java.util.List)
  print scripts.class.name.equalsIgnoreCase('java.util.ArrayList')||scripts.class.name.equalsIgnoreCase('net.sf.json.JSONArray')
  print !scripts.isEmpty()

  def needTag = false
  def releaseDetails = ''
  if (scripts != null && (scripts instanceof java.util.List) && !scripts.isEmpty()) {
    scripts.each { cmd ->
        print cmd
        releaseDetails = releaseDetails + "-" + cmd
        def ekscmd = cmd
        if (needProfile) {
          ekscmd = "${cmd} --profile ${profile}"
        }
        try {
          sh "${ekscmd}"
        } catch(e) {
          print e.getMessage()
        }
        needTag = true
    }
  } else {
    print 'xxxx, wrong'
  }
  return [
    needTag: needTag,
    releaseDetails: releaseDetails
  ]

}
