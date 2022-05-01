package com.aristotlecap.pipeline.devRelease;

def build(projectTypeParam, gitBranchName, buildNumber,  builderTag, gitScm,
          gitCommit, appDtrRepo, organizationName, appGitRepoName, prodEnv,
          releaseVersion, templates, secrets, startServerScripts, stopServerScripts,
          remoteAppUser, secretsRemoteDests, appArtifacts, appArtifactsRemoteDests, nonProdHostsMap,
          prodHosts, preInstallArtifacts, preInstallArtifactsDests) {

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
  echo projectTypeParam

  // Clean workspace before doing anything
  deleteDir()
  git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
  sh "git reset --hard ${gitCommit}"

  def commons = new com.aristotlecap.pipeline.Commons()
  def manualReleaseBranchName = "release-${gitBranchName}-${buildNumber}"
  echo manualReleaseBranchName

  //create a release branch, checkout it and do the release
  sh """
    git checkout -b $manualReleaseBranchName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push --set-upstream origin $manualReleaseBranchName'
  """

  commons.mavenReleaseBuild(gitBranchName, manualReleaseBranchName)

  def releaseTagName=sh(returnStdout: true, script: "git describe --abbrev=0 --tags").trim()
  echo "releaseTagName = $releaseTagName"
  sh "git checkout $releaseTagName"
  def releaseTagCommit=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
  echo "releaseTagCommit = $releaseTagCommit"
  sh 'echo "CONTAINER BUILT SUCCESSFULLY!"'

  def pom = readMavenPom file: 'pom.xml'
  print pom
  print pom.version
  def pomversion = pom.version
  def dtag = "${gitBranchName}-${pomversion}-${buildNumber}"
  echo builderTag

  currentBuild.displayName = currentBuild.displayName + "-${pomversion}-released"

  def tagForApprove = currentBuild.displayName

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
  commons.archive(appArtifacts)

  def artifactId = pom.artifactId
  echo "artifactId = ${artifactId} need to be dropped"
  sh """
    ssh anthill@antprod1.westernasset.com -i "/home/jenkins/.ssh/id_rsa"  "~/removeSnapshot.sh" $artifactId
  """

  return tagForApprove
}
