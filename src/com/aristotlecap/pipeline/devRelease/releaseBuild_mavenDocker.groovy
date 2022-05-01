package com.aristotlecap.pipeline.devRelease;

def build(gitBranchName, buildNumber, builderTag,
          gitScm, gitCommit, projectTypeParam, appDtrRepo,
          organizationName, appGitRepoName, prodEnv, drEnv, liquibaseChangeLog,
          liquibaseBuilderTag, userReleaseVersion, imageSnapshotTag, templates,
          secrets, nonProdEnvs, baseDisplayTag, dockerfileToTagMapString) {

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
  echo imageSnapshotTag

  // Clean workspace before doing anything
  deleteDir()

  git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
  sh "git reset --hard ${gitCommit}"

  def commons = new com.aristotlecap.pipeline.Commons()
  def manualReleaseBranchName = "release-${imageSnapshotTag}"
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

  currentBuild.displayName = currentBuild.displayName + "-${pomversion}-released"
  if (baseDisplayTag != 'null') {
    currentBuild.displayName = "${baseDisplayTag}-${pomversion}-released"
  }


  baseDisplayTag = currentBuild.displayName

  println "baseDisplayTag :: "
  println baseDisplayTag

  def imageTag = "${gitBranchName}-${pomversion}-${buildNumber}"

  if (dockerfileToTagMapString != 'null') {
    def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
    def dockerfileToTagMap = commons.getMapFromString(dockerfileToTagMapString)
    dockerfileToTagMap.each{ dockerFile, tag ->
      def dockerfilefullpath = "${workspace}/${dockerFile}"
      commons.dockerBuild("${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_NONPROD_KEY}", "${appDtrRepo}", "${tag}", organizationName, appGitRepoName, gitBranchName, gitCommit, dockerfilefullpath, 'Docker Build')
    }
  } else {
    commons.dockerBuild("${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_NONPROD_KEY}", "${appDtrRepo}", "${imageTag}", organizationName, appGitRepoName, gitBranchName, gitCommit, 'null', 'Release Docker Build')
  }

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

  def artifactId = pom.artifactId
  echo "artifactId = ${artifactId} need to be dropped"
  sh """
    ssh anthill@antprod1.westernasset.com -i "~/.ssh/id_rsa"  "~/removeSnapshot.sh" $artifactId
  """

  return imageTag + '::' + baseDisplayTag
}
