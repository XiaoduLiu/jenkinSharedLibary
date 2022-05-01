package com.aristotlecap.pipeline.operationRelease;

def build(gitBranchName, buildNumber, imageTags,
          gitCommit, gitScm, organizationName, appGitRepoName,
          appDtrRepo) {
  def commons = new com.aristotlecap.pipeline.Commons()

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh')
  ]) {
    node(POD_LABEL) {
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
        echo workspace

        def gitTag = commons.getImagesTags(imageTags)
        gitTag = gitTag.substring(0,20) + "-${buildNumber}-release"

        stage("GIT Tag") {
          echo imageTags
          echo gitTag

          //git release
          sh """
            ls -la
            git config --global user.email "jenkins@westernasset.com"
            git config --global user.name "Jenkins Agent"
            git config --global http.sslVerify false
            git config --global push.default matching
            git config -l

            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitTag -m "Release for ${gitTag}" '
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitTag'
          """
        }

        stage("Deploy to PROD") {
          currentBuild.displayName = "${gitBranchName}-${buildNumber}-${gitTag}"

          echo buildNumber
          echo "imageTags=${imageTags}"

          //sh 'set'

          container('docker') {
            imageTags.split("\n").each { tag ->
              echo tag
              def nonprod = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${organizationName}/${tag}"
              def prod = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${tag}"
              commons.removeTag(nonprod)
              commons.removeTag(prod)
              docker.withRegistry("https://${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_CREDENTIAL}") {
                sh """
                  docker pull $nonprod
                  docker tag $nonprod $prod
                  docker push $prod
                """
              }
            }
          }
        }
  	  } catch (err) {
  	    currentBuild.result = 'FAILED'
  	    throw err
  	  }
    }
  }
}
