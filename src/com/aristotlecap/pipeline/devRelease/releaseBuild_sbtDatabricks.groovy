package com.aristotlecap.pipeline.devRelease;

def build(gitBranchName, gitScm, gitCommit, buildNumber, builderDtrUri, builderRepo,
                       builderTag, organizationName, appGitRepoName, nonProdEnvs, qaEnvs, templates, secrets,
                       appArtifactsString, deployEnv, baseDisplayTag) {

  //sh 'git config -l'
  //echo sh(script: 'env|sort', returnStdout: true)
  sh """
    git config --global user.email "jenkins@westernasset.com"
    git config --global user.name "Jenkins Agent"
    git config --global http.sslVerify false
    git config --global push.default matching
    git config -l
  """
  echo gitScm
  echo gitBranchName
  echo gitCommit

  // Clean workspace before doing anything
  deleteDir()
  git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
  sh "git reset --hard ${gitCommit}"

  def commons = new com.aristotlecap.pipeline.Commons()
  def manualReleaseBranchName = "release-${baseDisplayTag}"
  echo manualReleaseBranchName

  //create a release branch, checkout it and do the release
  sh """
    git checkout -b $manualReleaseBranchName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push --set-upstream origin $manualReleaseBranchName'
  """

  stage ('SBT Release?') {
    docker.withRegistry("https://${builderDtrUri}") {
      docker.image("${builderRepo}:${builderTag}").inside("--network=jenkins") {
        echo "$USER"
        //sh 'ls -la /home/jenkins'
        //sh 'ls -la /home/jenkins/.ssh'
        //sh 'ls -la /home/jenkins/vault'
        //sh 'cat /home/jenkins/.ssh/config'
        //sh 'set'
        //echo sh(script: 'env|sort', returnStdout: true)
        echo "EXECUTE MAVEN SNAPSHOT BUILD"
        sh 'sbt -Dsbt.log.noformat=true "release with-defaults"'
        sh """
          git config -l
          ls -la
          ls -la ./target
          ls -la ./target/scala-2.12
        """
      }
    }
  }

  def releaseTagName=sh(returnStdout: true, script: "git describe --abbrev=0 --tags").trim()
  echo "releaseTagName = $releaseTagName"
  sh "git checkout $releaseTagName"
  def releaseTagCommit=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
  echo "releaseTagCommit = $releaseTagCommit"
  sh 'echo "CONTAINER BUILT SUCCESSFULLY!"'

  //switch back to the original branch (master or jenkins branch)
  def removeBranchName = ":${manualReleaseBranchName}"
  echo "removeBranchName = ${removeBranchName}"

  sh """
    git checkout $gitBranchName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git reset --hard'
    git merge $manualReleaseBranchName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -f'
    git branch -D $manualReleaseBranchName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $removeBranchName'
  """

}
