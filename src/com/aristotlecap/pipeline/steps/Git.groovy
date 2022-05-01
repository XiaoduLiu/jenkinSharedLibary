package com.aristotlecap.pipeline.steps

def useJenkinsUser() {
    sh """
        git config --global user.email "jenkins@westernasset.com"
        git config --global user.name "Jenkins Agent"
        git config --global http.sslVerify false
        git config --global push.default matching
        ssh-agent sh -c 'ssh-add /home/jenkins/.ssh/ghe-jenkins'
    """
}

def gitConfig() {
    sh """
        git config --global user.email "jenkins@westernasset.com"
        git config --global user.name "Jenkins Agent"
        git config --global http.sslVerify false
        git config --global push.default matching
    """
}


def createBranch(newBranchName, basebranch = 'master') {
  try {
    gitConfig()

    def returnString = sh(returnStdout: true, script: "git ls-remote --heads origin ${newBranchName}").trim()
    println returnStdout
    println 'xliu find it'


  } catch(e) {
    error(e.getMessage())
  }
}

return this
