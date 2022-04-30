package com.westernasset.pipeline.util

import com.westernasset.pipeline.Commons

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

def isEnvAvailable(environment) {
  def bool = false
  def files = findFiles(glob: "*.${environment}-request")
  if (files != null && files.size() > 0) {
    bool = true
  }
  return bool
}

def commitFiles(branchName, needGitUpdate=false, createRelease=false, commitMessage='', tagName=''){
  gitConfigSetup()
  if (needGitUpdate) {
    sh """
      git add .
      git commit -m ${commitMessage}
      ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -f'
    """
  }
  if (createRelease) {
    sh """
      ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a ${tagName} -m ${tagName}'
      ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin ${tagName}'
    """
  }
}

def mergeToMaster(branchName) {
  gitConfigSetup()
  def removeBranchName = ":${branchName}"
  sh """
    git checkout master
    git pull
    git remote -v
    git merge $branchName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -f'
    git branch -D $branchName
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $removeBranchName'
  """
}

def processUserRequestForGivingEnv(config, environment, tagName, stageEnv) {
  def needGitUpdate = false
  def requestFileExist = false
  def branchName = config.branch_name
  bool = isEnvAvailable(stageEnv)
  if (bool) {
    container('builder') {
      withCredentials([usernamePassword(credentialsId: "${env.CCLOUD_ADMIN}", usernameVariable: 'ccloud_email', passwordVariable: 'ccloud_password')]) {
        //echo sh(script: 'env|sort', returnStdout: true)
        sh """
          pwd
          cat $workspace/scripts/ccloud_login.sh
          chmod +x $workspace/scripts/ccloud_login.sh
          $workspace/scripts/ccloud_login.sh $ccloud_email '$ccloud_password'
        """
      }
      try {
        def files = findFiles(glob: "*.${environment}-request")
        for(file in files){
          if (environment == 'prod') {
            currentBuild.displayName = currentBuild.displayName + "-prod"
          } else {
            currentBuild.displayName = "${config.branch_name}-${config.build_number}-${config.releaseVersion}-${environment}"
          }
          def filename = file.name
          println 'working here =' + filename
          //move the processed file to history folder
          def dateString = new Date().format("yyyyMMddHHmmss")
          def filenamedt = "${filename}-${dateString}"

          /*
          sh """
            mv $workspace/$filename $workspace/history/$filename
            mv $workspace/history/$filename $workspace/history/$filenamedt
          """
          */

          def bool1 = processRequestFile(config, filename, filenamedt)
          if (!needGitUpdate) {
            needGitUpdate = bool1
          }

          requestFileExist=true
        }
        println requestFileExist
      } catch(e) {
        e.printStackTrace()
        try {
          commitFiles(branchName, true, false, currentBuild.displayName, '')
        } catch(err) {}
        error(e.getMessage())
      }
    }
  }
  if (requestFileExist) {
    commitFiles(branchName, needGitUpdate, needGitUpdate, currentBuild.displayName, tagName)
  }
  return requestFileExist
}

def runScript(scriptFile, scriptDataList, action) {
  println scriptFile
  def needGitUpdate = false
  for(js in scriptDataList) {
    def commandLineArguments = ''
    def outputInstruction
    def removeInstruction
    def filepath = ''
    for(key in js.keySet()) {
      if (key == 'output') {
        outputInstruction = js[key]
      } else if (key == 'remove') {
        removeInstruction = js[key]
      } else {
        def value = js[key]
        if (key.contains('-id')) {
          value = loadResourceKey(js[key], 'id')
          // store filepath to remove if action is delete
          if (value != null)
            filepath = js[key]
        }
        else if (key.contains('topic')) {
          value = loadResourceKey(js[key], 'topic_name')
          // store filepath to remove if action is delete
          if (value != null)
            filepath = js[key]
        }
        else if (key == 'optional-flags') {
          value = getOptionalParameters(js[key])
        }
        println key + ":" + value
        if (value != null) {
          commandLineArguments = commandLineArguments + " " + value
        }
      }
    }
    if (outputInstruction != null) {
      needGitUpdate = true
    }
    def outputString = sh(script: "bash ${workspace}/${scriptFile} ${commandLineArguments}", returnStdout: true)
    def containSecrets = findIfContainsSecert(outputInstruction)
    if (!containSecrets) {
      println outputString
    }
    saveOutput(outputString, outputInstruction)
    removeOutput(removeInstruction)
  }
  return needGitUpdate
}

def findIfContainsSecert(outputInstruction) {
  def bool = false
  if (outputInstruction!=null) {
    def type = outputInstruction.type
    if (type == 'vault') {
      bool = true
    }
  }
  return bool
}

def getOptionalParameters(js) {
  def optional = ''
  for (key in js.keySet()) {
    def value = js[key]
    println key + "::" + js[key]
    if (key.contains('-id')) {
      value = loadResourceKey(js[key], 'id')
    }
    def actualKey = key.replace("-id", "")
    optional = optional + "--" + actualKey + " " + value + " "
  }
  return optional
}

def saveOutput(outputString, outputInstruction) {
  if (outputInstruction!=null) {
    def type = outputInstruction.type
    def path = outputInstruction.path
    if (type == 'local') {
      if (outputString != null && outputString.contains('{')) {
        writeJSON file: "${workspace}/${path}", json: outputString, pretty: 2
      } else {
        writeFile file: "${workspace}/${path}", text: outputString
      }
      sh """
        cat $workspace/$path
      """
    } else if (type == 'vault') {
      writeJSON file: "${workspace}/vault.json", json: outputString, pretty: 2
      dir("${workspace}") {
        withCredentials([string(credentialsId: "${env.JENKINS_SECRET_SUPER_TOKEN}", variable: 'VAULT_TOKEN')]) {
          withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
            sh """
              vault write $path @vault.json
              rm ./vault.json
            """
          }
        }
      }
    }
  }
}

def removeOutput(removeInstruction) {
  if (removeInstruction!=null) {
    def type = removeInstruction.type
    def path = removeInstruction.path
    if (type == 'local') {
      sh """
        rm -rf ${workspace}/${path}
      """
    } else if (type == 'vault') {
      dir("${workspace}") {
        withCredentials([string(credentialsId: "${env.JENKINS_SECRET_SUPER_TOKEN}", variable: 'VAULT_TOKEN')]) {
          withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
            sh """
              vault delete $path
            """
          }
        }
      }
    }
  }
}

def loadResourceKey(resourceKeyFile, keyField) {
  def value
  if (fileExists("${workspace}/${resourceKeyFile}")) {
    echo "Yes, ${workspace}/${resourceKeyFile} exists"
    def keyJson = readJSON file: "${workspace}/${resourceKeyFile}"

    value = keyJson[keyField]
  }
  return value
}

def processRequest(scriptFolder, filename) {
  def config_json = "${workspace}/${filename}"

  println config_json
  def requestJson = readJSON file: config_json
  println requestJson
  def needGitUpdate = false

  for (key in requestJson.keySet()) {
    println key
    def value = requestJson[key]
    println value
    def scriptFile=scriptFolder+'/'+key+'.sh'
    println scriptFile
    def bool = runScript(scriptFile, value, key)
    if (!needGitUpdate) {
      needGitUpdate = bool
    }
  }

  // Move config_json to processed folder
  def dateString = new Date().format("yyyyMMddHHmm")
  int sep = config_json.lastIndexOf('/') + 1;

  String file = config_json.substring(sep) + '-' + dateString;
  String create_path = config_json.substring(0, sep) + 'processed/';
  String full_path = create_path + file

  sh """
    mkdir -p $create_path; mv $config_json $full_path
  """

  needGitUpdate = true
  return needGitUpdate
}

def MoveKeyValueToHistory(key, value, filename, filenamedt) {

  def historyFileName =  "$workspace/history/$filenamedt".toString()
  println ('MoveKeyValueToHistory ' + key + ' ' + value + ' ' + filename + ' ' + filenamedt + ' ' + historyFileName)

  // Read Current Request File
  def requestJson = readJSON file: filename

  requestJson.remove(key) // Remove Key..
  if (requestJson.size() == 0) {
    // remove empty json file...
    sh """
        rm -rf $filename
    """
  }
  else {
    writeJSON file: filename, json: requestJson, pretty: 2 // Overwrite request file
  }

  // Create File In History
  def historyRequestJson
  if (fileExists(historyFileName)) {
    historyRequestJson = readJSON file: historyFileName
  }
  else {
    historyRequestJson = [:]
  }

  historyRequestJson[key] = value
  writeJSON file: historyFileName, json: historyRequestJson, pretty: 2

}

def processRequestFile(config, filename, filenamedt) {
  def needGitUpdate = false
  def fileJson = readJSON file: "${workspace}/${filename}"
  for (key in fileJson.keySet()) {
    def requestFile = fileJson[key]
    println requestFile
    def scriptFolder = findFolder(config.scriptRoot, requestFile)
    println scriptFolder
    def bool = processRequest(scriptFolder, requestFile)
    if (!needGitUpdate) {
      needGitUpdate = bool
    }

    MoveKeyValueToHistory(key, requestFile, filename, filenamedt)
    needGitUpdate = true

  }
  return needGitUpdate
}

def findFolder(root, str) {
  def str1 = str.substring(str.indexOf("/")+1)
  def str2 = str1.reverse()
  def sub1 = str2.substring(str2.indexOf("/")+1)
  def sub = sub1.reverse()
  return root+'/'+sub
}
