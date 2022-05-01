package com.aristotlecap.pipeline.util

import groovy.json.JsonSlurper

def gitCommitPush(connectorName) {
  //push to github
  gitConfigSetup()
  sh """
    git add .
    git commit -m "new connector $connectorName added to request" --allow-empty
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push --force'
  """
}

def secretProcess(templates, secrets, connectorName, deployEnv, organizationName, appGitRepoName, isProd) {
  def commons = new com.aristotlecap.pipeline.Commons()
  try {
    def part = (isProd)? "prod":"nonprod"
    def secretRootBase = "secret/${organizationName}/${appGitRepoName}/${part}"
    def secretRoot = (isProd)? "${secretRootBase}/${connectorName}":"${secretRootBase}/${connectorName}/${deployEnv}"

    def appRoleName = "${organizationName}-${appGitRepoName}-${part}"
    def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, isProd);

    echo "application vault auth token -> ${appVaultAuthToken}"
    commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
  } catch (err) {
    currentBuild.result = 'FAILED'
    throw err
  }
}

def deploy(config, repo, deployEnv, isProdDeploy) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def connectors = config.connectors
  for(connector in connectors) {
    //vault process
    println connector
    def template = connector.connectorTemplate
    def secret = 'template.json'
    def connectorName = (isProdDeploy)? connector.connectorName : deployEnv + "-" + connector.connectorName
    def script = connector.script
    secretProcess(template, secret, connector.connectorName, deployEnv, repo.organizationName, repo.appGitRepoName, isProdDeploy)

    def fileContents = readFile("${workspace}/${secret}")

    fileContents = fileContents.replaceAll('connector_name', connectorName)

    writeFile file: "${workspace}/${secret}", text: fileContents

    dir ("${workspace}") {
      withEnv(["CURLMOPT_MAXCONNECTS=-1"]) {
        sh """
          chmod +x ./$script
          ./$script ./$secret
          cat $script
        """
      }
    }
  }
}

def getStringFromMap(map, mapDevider) {
  println 'map ->' + map
  def mymapString = ""
  map.each{ k, v ->
    println 'key->' + k
    println 'value->' + v
    if (mymapString == "") {
       mymapString = "${k}" + mapDevider + "${v}"
    } else {
       mymapString = mymapString +":::"+"${k}" + mapDevider + "${v}"
    }
  }
  println 'mapstring->' + mymapString
  return mymapString
}

def getMapFromString(str, mapDevider) {
  def md = str.split(":::")
  def dem = md.length

  def map = [:]
  def i = 0
  while(i<dem) {
    def p = md[i]
    print "\n" + p
    def mmd = p.split(mapDevider)
    def k = mmd[0]
    def v ='null'
    if (mmd.length > 1) {
      v = mmd[1]
    }
    map[k] = v
    i=i+1
  }
  print map
  return map
}

def gitConfigSetup() {
  sh """
    git config --global user.email "jenkins@westernasset.com"
    git config --global user.name "Jenkins Agent"
    git config --global http.sslVerify false
    git config --global push.default matching
    git config --global hub.protocal ssh
    git config --global hub.host github.westernasset.com
    git config -l
  """
}

def mergeToMaster(branchName) {
  gitConfigSetup()
  def removeBranchName = ":${branchName}"
  sh """
    git checkout master
    git pull
    git remote -v
    git merge $branchName
    git checkout --ours Jenkinsfile
    git add .
    git commit -m resolve-merge-conflict
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -f'
    git branch -D $branchName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $removeBranchName'
  """
}

def commitRelease(tagName) {
  gitConfigSetup()
  sh """
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $tagName -m $tagName'
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $tagName'
  """
}
