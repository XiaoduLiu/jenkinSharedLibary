package com.aristotlecap.pipeline.util

def createBranch(newBranchName) {
  try {
    sh """
      git checkout $newBranchName
      git branch
      git pull
    """
  } catch(e) {
    //println e.getMessage()
    //make a branch and push to remote
    gitConfigSetup()
    sh """
      git checkout master
      git checkout -b $newBranchName
      ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push --set-upstream origin $newBranchName'
      git checkout $newBranchName
      git branch
      git pull
    """
  }
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

def checkInput(map) {
  def action = map.Action.toLowerCase()
  def authentication = map.Authentication_Type
  def userName = map.User_Account_Login_Name
  def publicKey = map.User_Account_Public_Key
  def password = map.User_Account_Login_Password
  if (!userName?.trim()) {
    error('User account name is required!')
  }
  if (authentication == 'public-key' && (!userName?.trim()||!publicKey?.trim())) {
    error('User account name & user account SSH public key required for public-key type of the authtnetication')
  }
  if (authentication == 'password' && (!userName?.trim()||!password?.trim())) {
    error('User account name & user account password are required for password type of the authtnetication')
  }
}

def getRequestFileJson(loginUser, changeType) {
  def requestFileName = "${loginUser}.request"
  def requestFileJson
  println requestFileName
  println "${workspace}"
  sh """
    pwd
    ls -la
    git branch
  """
  def changeTypeList
  if (fileExists("${workspace}/${requestFileName}")) {
    echo "Yes, ${workspace}/${requestFileName} exists"
    requestFileJson = readJSON file: "${workspace}/${requestFileName}"
    println requestFileJson
  }
  requestFileJson = (requestFileJson==null)?[:] : requestFileJson
  changeTypeList = requestFileJson[changeType]
  if (changeTypeList == null) {
    changeTypeList = []
  }
  requestFileJson[changeType] = changeTypeList
  writeJSON file: "${workspace}/${requestFileName}", json: requestFileJson, pretty: 2
  return requestFileJson
}

def checkClientExist(action, clientName) {
  def jsonObj
  if (fileExists("${workspace}/UserAccountList.json")) {
    echo "Yes, ${workspace}/UserAccountList.json exists"
    jsonObj = readJSON file: "${workspace}/UserAccountList.json"
    println jsonObj
  }
  if (action == 'delete' && jsonObj == null) {
    error("Can not delete not existed user account ${clientName}!")
  }
  if (action == 'create' && jsonObj != null) {
    def client = jsonObj[clientName]
    if (client != null) {
      error("User Account ${clientName} is already exist!")
    }
  }
  if (action == 'delete' && jsonObj != null) {
    def client = jsonObj[clientName]
    if (client == null) {
      error("User Account ${clientName} is not exist for delete!")
    }
  }
}

def processRequest(loginUser, requestFileJson, map, organizationName, appGitRepoName) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def action = map.Action.toLowerCase()
  def authentication = map.Authentication_Type
  def userName = map.User_Account_Login_Name.toString()
  def publicKey = "'%s'".format(map.User_Account_Public_Key)
  def password = "'%s'".format(map.User_Account_Login_Password)
  if (action == 'create') {
    def reqJson = [:]
    reqJson.authentication = authentication
    reqJson.stackName = "wam-sftp-client-${userName}-stack"
    reqJson.bucketName = "wa-vendor-x"
    reqJson.userName = userName
    reqJson.password = password
    reqJson.publicKey = publicKey

    //process vault
    vaultProcess(false, organizationName, appGitRepoName, userName, reqJson)

    def vaultUrl = "https://wamvault.westernasset.com:8200/ui/vault/secrets/secret/show/${organizationName}/${appGitRepoName}/nonprod/${userName}"
    println 'Please review the input from the create user account request -> ' + vaultUrl

  }
  //create the record in the control file
  if (!requestFileJson[action].contains(userName)) {
    requestFileJson[action].push(userName)
  }
  writeJSON file: "${workspace}/${loginUser}.request", json: requestFileJson, pretty: 2

  //commit the change to git
  gitCommitPush(action+'-'+userName)
}

def vaultProcess(isProd, organizationName, appGitRepoName, userName, reqJson) {
  //keep in vault nonprod side
  def commons = new com.aristotlecap.pipeline.Commons()
  def envName = (isProd)? 'prod' : 'nonprod'
  def jsonFile = "${userName}.json"
  def vaultPath = "secret/${organizationName}/${appGitRepoName}/${envName}/${userName}"
  def appRoleName = organizationName + '-' + appGitRepoName + '-' + envName
  def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, isProd)

  def userJson

  if (!isProd) {
    writeJSON file: "${workspace}/${jsonFile}", json: reqJson, pretty: 2
    dir("${workspace}") {
      container('vault') {
        withEnv(["VAULT_TOKEN=${appVaultAuthToken}"]) {
          sh """
            vault write $vaultPath @$jsonFile
            rm $jsonFile
          """
        }
      }
    }
  } else {
    def nonProdVaultPath = "secret/${organizationName}/${appGitRepoName}/nonprod/${userName}"
    dir("${workspace}") {
      container('vault') {
        withEnv(["VAULT_TOKEN=${appVaultAuthToken}", "SECRET_ROOT=${nonProdVaultPath}"]) {
          sh """
            dir
            consul-template -vault-renew-token=false -once -template bin/wam.ctmpl:$jsonFile
            cp $jsonFile bin/wam.json
            ls -la bin
            vault write $vaultPath @$jsonFile
            rm $jsonFile
            vault delete $nonProdVaultPath
          """
          userJson = readJSON file: "bin/wam.json"
        }
      }
    }
  }
  return userJson
}

def vaultProcessForDelete(organizationName, appGitRepoName, userName) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def appRoleName = organizationName + '-' + appGitRepoName + '-prod'
  def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, true)
  def prodVaultPath = "secret/${organizationName}/${appGitRepoName}/prod/${userName}"
  def jsonFile = "${userName}.json"
  dir("${workspace}") {
    container('vault') {
      withEnv(["VAULT_TOKEN=${appVaultAuthToken}", "SECRET_ROOT=${prodVaultPath}"]) {
        sh """
          consul-template -vault-renew-token=false -once -template bin/wam.ctmpl:$jsonFile
          cp $jsonFile bin/wam.json
          rm $jsonFile
        """
        userJson = readJSON file: "bin/wam.json"
      }
    }
  }
  return userJson
}

def vaultDelete(organizationName, appGitRepoName, userName) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def appRoleName = organizationName + '-' + appGitRepoName + '-prod'
  def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, true)
  def prodVaultPath = "secret/${organizationName}/${appGitRepoName}/prod/${userName}"
  dir("${workspace}") {
    container('vault') {
      withEnv(["VAULT_TOKEN=${appVaultAuthToken}"]) {
        sh """
          vault delete $prodVaultPath
        """
      }
    }
  }
}

def cdkProcessing(userName, userJson) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def stack = userJson.stackName
  def af = 'bin/awsSftpTranferUser.js'
  def profile = 'prod'
  def tags = '--tags wam:git-organizationn=shared-services ' +
             '--tags wam:git-repository=aws-sftp-transfer-users ' +
             '--tags wam:product-platform=sftp-transfer ' +
             '--tags wam:product-name=shared-services ' +
             '--tags wam:category=service ' +
             '--tags wam:environment=prod '
  dir("${workspace}") {
    container('cdk') {
      commons.npmBuild()
      sh(script: "cdk synth ${stack} --app ${af} --profile ${profile}", returnStdout: true)
      sh """
        cdk diff $stack --app $af --profile $profile
        cdk deploy $stack --app $af --require-approval never $tags --profile $profile
      """
    }
  }
}

def createDefaultFolders(userName) {
  container('cdk') {
    sh """
      aws s3api put-object --bucket wa-vendor-x --key $userName/inbound/ --profile prod
      aws s3api put-object --bucket wa-vendor-x --key $userName/outbound/ --profile prod
    """
  }
}

def cdkDeleteProcessing(userName, userJson) {
  def commons = new com.aristotlecap.pipeline.Commons()
  def stack = userJson.stackName
  def af = 'bin/awsSftpTranferUser.js'
  def profile = 'prod'
  dir("${workspace}") {
    container('cdk') {
      commons.npmBuild()
      sh """
        cdk destroy $stack --app $af --require-approval never --force --profile $profile
      """
    }
  }
}

def addUserToLocalUserList(userName, organizationName, appGitRepoName) {
  def vaultPath = "secret/${organizationName}/${appGitRepoName}/prod/${userName}".toString()
  usersJson = [:]
  if (fileExists("${workspace}/UserAccountList.json")) {
    echo "Yes, ${workspace}/UserAccountList.json exists"
    usersJson = readJSON file: "${workspace}/UserAccountList.json"
    println usersJson
  }
  usersJson[userName] = vaultPath
  writeJSON file: "${workspace}/UserAccountList.json", json: usersJson, pretty: 2
}

def removeUserFromLocalList(userName) {
  jsonfile = [:]
  if (fileExists("${workspace}/UserAccountList.json")) {
    echo "Yes, ${workspace}/UserAccountList.json exists"
    jsonfile = readJSON file: "${workspace}/UserAccountList.json"
    println jsonfile
  }
  jsonfile.remove(userName)
  println 'after remove->' + userName
  println jsonfile
  writeJSON file: "${workspace}/UserAccountList.json", json: jsonfile, pretty: 2
}

def gitCommitPush(userName) {
  //push to github
  gitConfigSetup()
  sh """
    git add .
    git commit -m "$userName added to request" --allow-empty
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push --force'
  """
}

def commitRelease(tagName) {
  gitConfigSetup()
  sh """
    git add .
    git commit -m $tagName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -f'
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $tagName -m $tagName'
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $tagName'
  """
}

def moveProcessingFile(requesterId) {
  def dateString = new Date().format("yyyyMMddHHmmss")
  def newFilename = "${requesterId}.request-${dateString}"
  def filename = "${requesterId}.request"
  dir("$workspace") {
    sh """
      mv $filename history/$filename
      mv history/$filename history/$newFilename
    """
  }
}
