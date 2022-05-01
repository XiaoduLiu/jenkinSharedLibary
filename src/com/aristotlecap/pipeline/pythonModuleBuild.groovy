package com.aristotlecap.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, releaseVersion) {

  def organizationName
  def appGitRepoName
  def gitScm
  def gitCommit

  def commons = new com.aristotlecap.pipeline.Commons()

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'wheel', image: "${builderImage}", ttyEnabled: true, command: 'cat')
    ])  {
    node(POD_LABEL) {
      currentBuild.displayName  = "${gitBranchName}-${releaseVersion}-${buildNumber}"

      try {
        stage ('Clone') {
          // Clean workspace before doing anything
          deleteDir()
          checkout scm

          gitCommit=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
          echo gitCommit

          String gitRemoteURL = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
          echo gitRemoteURL

          gitScm = "git@github.westernasset.com:" + gitRemoteURL.drop(32)
          echo gitScm

          String shortName = gitRemoteURL.drop(32).reverse().drop(4).reverse()
          echo shortName

          def names = shortName.split('/')

          echo names[0]
          echo names[1]

          organizationName = names[0]
          appGitRepoName = names[1]

          appDtrRepo = organizationName + '/' + appGitRepoName
          echo "appDtrRepo -> ${appDtrRepo}"
        }

        stage('Build') {
          container('wheel') {
            sh """
              python3 setup.py sdist bdist_wheel
            """
          }
        }

      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  //select the nonprod awsAccount
  approveRelease(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                 gitScm, gitCommit, builderTag, releaseVersion)
}

def approveRelease(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                   gitScm, gitCommit, builderTag, releaseVersion) {

  def didAbort = false
  def didTimeout = false

  def releaseFlag

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"

  stage("Ready to Release?") {
    checkpoint "Ready to release"

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Release?')
      }
      releaseFlag = userInput
      println releaseFlag

    } catch(err) { // timeout reached or input false
    def user = err.getCauses()[0].getUser()
      if('SYSTEM' == user.toString()) { // SYSTEM means timeout.
        didTimeout = true
      } else {
        didAbort = true
        echo "Aborted by: [${user}]"
      }
    }
    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else if (didAbort) {
      // do something else
      echo "this was not successful"
      currentBuild.result = 'SUCCESS'
    } else {
      releaseLogic(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                   gitScm, gitCommit, builderTag, releaseVersion)
    }
  }
}


def releaseLogic(projectTypeParam, gitBranchName, buildNumber, organizationName, appGitRepoName,
                 gitScm, gitCommit, builderTag, releaseVersion) {

  def commons = new com.aristotlecap.pipeline.Commons()

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'wheel', image: "${builderImage}", ttyEnabled: true, command: 'cat')
      ],
      volumes: [
        persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
    ]) {
    node(POD_LABEL) {
      try {
        echo currentBuild.displayName
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def gitReleaseTagName = "${appGitRepoName}-${releaseVersion}"
        sh """
          git config --global user.email "jenkins@westernasset.com"
          git config --global user.name "Jenkins Agent"
          git config --global http.sslVerify false
          git config --global push.default matching
          git config -l

          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}" '
          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
        """

        stage('Release') {
          container('wheel') {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.ARTIFACTORY_CREDENTIAL}",
              usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
              def url = "${env.ARTIFACTORY_LINK}"
              sh """
                python3 setup.py sdist bdist_wheel
                twine upload --repository-url https://$USERNAME:$PASSWORD@$url -u $USERNAME -p $PASSWORD dist/*
              """
            }
          }

        }

      } catch (err) {
        err.printStackTrace()
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
}
