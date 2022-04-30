package com.westernasset.pipeline.builds

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*

def deploy(builderTag, repoScm, buildNumber, branchName, environment) {

  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def ccloud = new CCloud()
  def repo
  def yaml

  def builderImage = BuilderImage.fromTag(env, builderTag).getImage()
  print builderImage

  pod.node(
    containers: [
      ccloud.containerTemplate(builderImage)
    ],
    volumes: [
      ssh.keysVolume()
    ]
  ) {
    stage('Clone') {
      if (environment != 'prod') {
        repo = gitScm.checkout()
        deleteDir()
        git url: repo.getScm(), credentialsId: 'ghe-jenkins', branch: "${branchName}"
      } else {
        deleteDir()
        println "working here at prod release "
        println repoScm
        println branchName
        git url: repoScm, credentialsId: 'ghe-jenkins', branch: "${branchName}"
      }
    }

    def nonprodDeployFlag = checkBranchStatus()
    ccloud.container {
      loginToConfluentCloud()
      if (environment == 'prod') {
        stage('Deploy to Prod') {
          print "Deploy to -> ${environment}"
          processingEnvFiles(environment, branchName, buildNumber)
        }
      } else {
        if (nonprodDeployFlag) {
          stage('Deploy to Non-Prod') {
            print "Deploy to -> ${environment}"
            def envs = ["dev", "qa", "uat", "imqa", "imuat", "sandbox"]
            if (environment == 'ALL') {
              for(v in envs) {
                print "processing environment ->" + v
                processingEnvFiles(v, branchName, buildNumber)
              }
            } else {
              processingEnvFiles(environment, branchName, buildNumber)
            }
          }
        } else {
          stage('Deploy to nonprod') { }
        }
      }
    }

    if (environment == 'prod') {
      stage("Update the Git") {
        def message = "From ${branchName} update ${environment} from builderNumber ${buildNumber}"
        def tagName = "${branchName}-${environment}-${buildNumber}"
        gitTag(message, tagName, branchName)
      }
    } else {
      if (nonprodDeployFlag) {
        stage("Update the Git") {
          def message = "From ${branchName} update ${environment} from builderNumber ${buildNumber}"
          def tagName = "${branchName}-${environment}-${buildNumber}"
          gitTag(message, tagName, branchName)
        }
      } else {
        stage("Update the Git") { }
      }
    }
  }
  return repo
}

def checkBranchStatus() {
  int count = 0
  def envs = ["dev", "qa", "uat", "imqa", "imuat", "sandbox", "prod"]
  for (v in envs) {
    def files = findFiles(glob: "${v}.*")
    def fSize = (files !=null)?files.size():0
    count = count + fSize
    //print "files size for ->" + v + " is ->" + fSize
  }
  return (count > 0)
}

def loginToConfluentCloud() {
  stage('Login to Confluent') {
    withCredentials([usernamePassword(credentialsId: "${env.CCLOUD_ADMIN}", usernameVariable: 'ccloud_email', passwordVariable: 'ccloud_password')]) {
      //echo sh(script: 'env|sort', returnStdout: true)
      sh """
        chmod +x $workspace/scripts/ccloud_login.sh
        $workspace/scripts/ccloud_login.sh $ccloud_email '$ccloud_password'
      """
    }
  }
}

def processingEnvFiles(environment, branchName, buildNumber) {
  print "processing -> ${environment} files"

  //checkout the master branch to temp folder
  checkoutMasterToTemp()

  def files = findFiles(glob: "${environment}.*")
  for(file in files) {
    def filename = file.name
    println 'processing file -> ' + filename
    println 'after processing the file, remove it -> ' + filename

    //load it into json object
    def json = readJSON file: "${workspace}/${filename}"
    //processObject
    def createObjectList = json['create']
    def updateObjectList = json['update']
    def deleteObjectList = json['delete']

    if (createObjectList != null) {
      println "process create Topic logic"
      for (createObject in createObjectList) {
         topicOperation(environment, 'create', createObject)
      }
    } else if (updateObjectList != null) {
      println "process update Topic logic"
      for (updateObject in updateObjectList) {
         topicOperation(environment, 'update', updateObject)
      }
    } else if (deleteObjectList != null) {
      println "process delete Topic logic"
      for (deleteObject in deleteObjectList) {
         topicOperation(environment, 'delete', deleteObject)
      }
    }
  }

  //commit the master
  commitMaster("From ${branchName} update ${environment} from builderNumber ${buildNumber}")
  //merge master to branch
  mergeMaster("Merge master to ${branchName}", branchName)

  for(file in files) {
    def finename = file.name
    sh """
      rm ./$finename
    """
  }
  //remove the change file and commit & push
  gitCommitPush("From ${branchName} update ${environment} from builderNumber ${buildNumber}", branchName)
}

def topicOperation(environment, action, jsonObject) {
  print jsonObject

  def TOPIC_NAME = jsonObject['name']
  def OPTIONALS = ''
  if (jsonObject['optional-flags'])
    OPTIONALS = getOptionalParameters(jsonObject['optional-flags'])

  println("Env: " + environment + " Topic Name: " + TOPIC_NAME + ' OPTIONALS: ' + OPTIONALS)

  if (environment == 'prod') {
    sh """
      ccloud environment use $env.PROD_ENVIRONMENT
      ccloud kafka cluster use $env.PROD_CLUSTER
    """
  } else {
    sh """
      ccloud environment use $env.NONPROD_ENVIRONMENT
      ccloud kafka cluster use $env.NONPROD_CLUSTER
    """
  }

  try {
    def fileLoc = "temp/${env.TOPIC_REPO_NAME}/resources/topics/${TOPIC_NAME}"
    def RUN_CMD = sh(returnStdout: true, script: "ccloud kafka topic ${action} ${TOPIC_NAME} ${OPTIONALS}").toString().trim()
    if (action != 'delete') {
      def RUN_DESC = sh(returnStdout: true, script: "ccloud kafka topic describe -o json ${TOPIC_NAME}")
      saveJsonFile(fileLoc, RUN_DESC)
    } else {
      deleteJsonFile(fileLoc)
    }
  } catch(exp) {
    error(exp.getMessage())
  }

  sh """
    ccloud environment use $env.NONPROD_ENVIRONMENT
    ccloud kafka cluster use $env.NONPROD_CLUSTER
  """
}

def gitCommitPush(message, branchName) {
  gitConfigSetup()
  sh """
    pwd
    ls -la
    git add .
    git commit -m "${message}" --allow-empty
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -u origin $branchName --force'
  """
}

def  checkoutMasterToTemp() {
  gitConfigSetup()
  sh """
    pwd
    mkdir -p temp
    cd temp
    pwd
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git clone $env.TOPIC_REPO'
    cd $env.TOPIC_REPO_NAME
  """
}

def commitMaster(message) {
  gitConfigSetup()
  sh """
    pwd
    cd temp/$env.TOPIC_REPO_NAME
    git add .
    git commit -m "${message}"  --allow-empty
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -u origin master --force'
  """
}

def mergeMaster(message, branchName) {
  gitConfigSetup()
  sh """
    pwd
    rm -rf temp
    git checkout master
    git pull
    git checkout $branchName
    git merge master $branchName -m "${message}"
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -u origin $branchName --force'
  """
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

// Function copied from Shared Library. Used to parse 'optional-flags'
def getOptionalParameters(js) {
  def optional = ''
  for (key in js.keySet()) {
    def value = js[key]
    optional = optional + "--" + key + " " + value + " "
  }
  println 'optional parameters ->' + optional
  return optional
}

def saveJsonFile(fileLoc, jsonObject) {
  if (jsonObject.isEmpty()) return

  writeJSON file: "${workspace}/${fileLoc}", json: jsonObject, pretty: 2
  sh """
    cat $workspace/$fileLoc
  """
}

def deleteJsonFile(fileLoc) {
  try {
    sh """
      rm $workspace/$fileLoc
    """
  } catch(e) {
    println e.getMessage()
  }
}

def gitTag(message, tagName, branchName) {
  //tagging the master branch
  gitConfigSetup()
  sh """
    git branch
    git checkout master
    git pull
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a ${tagName} -m "${message}"'
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin ${tagName}'
  """
}
