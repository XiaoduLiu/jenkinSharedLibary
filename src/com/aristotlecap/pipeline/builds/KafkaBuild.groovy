package com.aristotlecap.pipeline.builds

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def deploy(builderTag, repoScm, buildNumber, branchName, environment, isProdDeploy, repo, crNumber, skip) {

  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def ccloud = new CCloud()
  def vault = new Vault()

  def builderImage = BuilderImage.fromTag(env, builderTag).getImage()
  print builderImage

  pod.node(
    containers: [
      ccloud.containerTemplate(builderImage),
      vault.containerTemplate()
    ],
    volumes: [
      ssh.keysVolume()
    ]
  ) {
    stage('Clone') {
      if (!isProdDeploy) {
        repo = gitScm.checkout()
        deleteDir()
        git url: repo.getScm(), credentialsId: 'ghe-jenkins', branch: "${branchName}"
      } else {
        deleteDir()
        git url: repoScm, credentialsId: 'ghe-jenkins', branch: "${branchName}"
        sh "git reset --hard ${repo.commit}"
      }
    }

    ccloud.container {
      loginToConfluentCloud(environment)
      processing(repoScm, buildNumber, branchName, environment, repo, isProdDeploy, skip)
    }

    if (!skip) {
      def tagName = (crNumber!=null)?"${branchName}-${environment}-${crNumber}-${buildNumber}":"${branchName}-${environment}-${buildNumber}"
      gitTag(tagName, tagName, branchName)
    }
  }

  return repo
}

def loginToConfluentCloud(environment) {
  stage('Login to Confluent') {
    withCredentials([usernamePassword(credentialsId: "${env.CCLOUD_ADMIN}", usernameVariable: 'ccloud_email', passwordVariable: 'ccloud_password')]) {
      //echo sh(script: 'env|sort', returnStdout: true)
      sh """
        /usr/local/bin/ccloud_login.sh $ccloud_email '$ccloud_password'
      """
    }
    sh """
      ccloud environment use $env.KAFKA_ENVIRONMENT
      ccloud kafka cluster use $env.KAFKA_CLUSTER
    """
  }
}

def processing(repoScm, buildNumber, branchName, environment, repo, isProdDeploy, skip) {
  //loading the changes.yaml
  def yaml = readYaml file: "${workspace}/changes.yaml"
  println yaml
  def processingList = []
  for(change in yaml.changes) {
    processingList.add(change)
  }

  stage('Process Topics') {
    if (!skip) {
      for( processing in processingList) {
        def tYaml = readYaml file: "${workspace}/${processing}"
        println tYaml
        for(topic in tYaml.topics) {
          workOnTopic(topic, environment, isProdDeploy)
        }
      }
    }
  }

  stage('Process Connectors') {
    if (!skip) {
      for( processing in processingList) {
        def tYaml = readYaml file: "${workspace}/${processing}"
        println tYaml
        for(connector in tYaml.connectors) {
          processConnector(connector, environment, repo, isProdDeploy)
        }
      }
    }
  }
}

def findFileName(changed) {
  def token = changed.split('/')
  String fileName
  token.each{  fileName = it }
  return fileName
}

def workOnTopic(topic, environment, isProdDeploy) {
  switch(topic.action) {
    case "delete":
      //println "delete topic ->" + topic
      deleteTopic(topic, environment, isProdDeploy)
      break
    case "update":
      //println "update topic ->" + topic
      updateTopic(topic, environment, isProdDeploy)
      break
    default:
      //println "create topic ->" + topic
      createTopic(topic, environment, isProdDeploy)
  }
}

def validateTopicName(topicName) {
  Boolean bool = false
  Set producerSet = ["trading", "compliance", "cars", "dap", "edm", "gdr", "imrs", "invest-one", "srm", "demo", "im"]
  Set domainSet=["analytics", "compliance", "reference", "prices", "pricing", "ratings", "security", "tickets", "broker", "position", "mock", "portfolio", "esg"]
  Set classificationSet = ["fct", "cdc", "cmd", "sys", "bulk", "compact"]
  Set formatSet = ["avro", "byte", "json", "xml", "xml-in-json"]
  def tokens = topicName.tokenize(".")

  if (tokens.size() == 5) {
    //println "producer ->" + tokens[0]
    //println "domain ->" + tokens[1]
    //println "classification ->" + tokens[2]
    //println "describe ->" + tokens[3]
    //println "format ->" + tokens[4]

    if (producerSet.contains(tokens[0]) &&
        domainSet.contains(tokens[1]) &&
        classificationSet.contains(tokens[2]) &&
        formatSet.contains(tokens[4])) {
        bool = true
    }
  }
  return bool
}

def deleteTopic(topic, environment, isProdDeploy) {
  for(topicName in topic.topicNames) {
    Boolean bool = validateTopicName(topicName.trim())
    if (bool) {
      deleteTopicAction(topicName, environment, isProdDeploy)
    } else {
      println "In correct topicName -> " + topic
    }
  }
}

def updateTopic(topic, environment, isProdDeploy) {
  for(topicName in topic.topicNames) {
    Boolean bool = validateTopicName(topicName)
    if (bool) {
      updateTopicActioin(topic, topicName, environment, isProdDeploy)
    } else {
      println "In correct topicName -> " + topic
    }
  }
}

def createTopic(topic, environment, isProdDeploy) {
  for(topicName in topic.topicNames) {
    Boolean bool = validateTopicName(topicName)
    if (bool) {
      createTopicAction(topic, topicName, environment, isProdDeploy)
    } else {
      println "IN CORRECT TOPIC NAME -> " + topicName
    }
  }
}

def createTopicAction(topic, topicName, environment, isProdDeploy){
  //check if the topic exist, if it is then do nothing, otherwise create it
  def topicRealName = (isProdDeploy)?topicName: "${environment}.${topicName}"
  if (topic.createOrReplace != null && topic.createOrReplace) {
    println("createOrReplace flag is set to be ${topic.createOrReplace} for topicName -> ${topicRealName}");
    deleteTopicAction(topicName, environment, isProdDeploy)
  }
  Boolean foundIt = checkTopicExists(environment, topicRealName)
  if (!foundIt) {
    println "create topic -> " + topicRealName + " from env -> " + environment
    def topicPatition = topic.partition
    def config = getOptionalParameters(topic.configs)
    sh """
      ccloud kafka topic create $topicRealName --partitions $topicPatition $config --dry-run
      ccloud kafka topic create $topicRealName --partitions $topicPatition $config
    """
    describTopic(topicRealName)
  }
}

def updateTopicActioin(topic, topicName, environment, isProdDeploy) {
  //check if the topic exist, it it is do update, otherwise error
  def topicRealName = (isProdDeploy)?topicName: "${environment}.${topicName}"
  Boolean foundIt = checkTopicExists(environment, topicRealName)
  if (foundIt) {
    println "update topic -> " + topicRealName + " from env -> " + environment
    def config = getOptionalParameters(topic.configs)
    sh """
      ccloud kafka topic update $topicRealName $config --dry-run
      ccloud kafka topic update $topicRealName $config
    """
    describTopic(topicRealName)
  }
}

def deleteTopicAction(topicName, environment, isProdDeploy) {
  //check if the topic exist, if it is do delete, otherwise do nothing
  def topicRealName = (isProdDeploy)?topicName: "${environment}.${topicName}"
  Boolean foundIt = checkTopicExists(environment, topicRealName)
  if (foundIt) {
    println "delete topic -> " + topicRealName + " from env -> " + environment
    sh """
      ccloud kafka topic delete $topicRealName
    """
  }
}

// Function copied from Shared Library. Used to parse 'optional-flags'
def getOptionalParameters(configs) {
  def optional = ""
  if (null != configs) {
    configs.each{ key, val ->
      if (optional?.trim()) {
        optional = optional + "," + key + "=" + val
      } else {
        optional = "--config " + key + "=" + val
      }
    }
  }
  println 'optional parameters ->' + optional
  return optional
}

def checkTopicExists(environment, topicName) {
  Boolean bool = true
  //println("Check Topic:- Env: " + environment + " Topic Name: " + topicName)
  def CHECK_TOPIC_COMMAND = """
                              ccloud kafka topic list -o json | jq '.[] | select (.name == "${topicName}") | .name'
                            """
  def topicError = sh(returnStdout: true, script: CHECK_TOPIC_COMMAND).toString().trim()
  if (!topicError?.trim()) {
    print 'WARNING: Topic Not Found.\n ' + topicName
    bool = false
  }
  return bool
}

def describTopic(topicName) {
  def DESCRIBE = "ccloud kafka topic describe ${topicName} -o json"
  def describe = sh(returnStdout: true, script: DESCRIBE)
  println describe
}

def processConnector(connector, environment, repo, isProdDeploy) {
  //println "Process connector -> " + connector + " from env -> " + environment
  def deleteConnectorName = connector.replaceConnectorName==null?
                      connector.connectorName:
                      connector.replaceConnectorName

  def deleteConnectorActualName = (isProdDeploy)? deleteConnectorName : "${environment}-${deleteConnectorName}"

  //check if the connector exist
  Boolean bool = checkConnector(deleteConnectorActualName, environment)
  println bool
  if (bool) {
    println "find it " + bool
    deleteConnector(deleteConnectorActualName, environment)
  }

  def createConnectorName = (isProdDeploy)? connector.connectorName : "${environment}-${connector.connectorName}"
  createConnector(createConnectorName, connector, environment, repo, isProdDeploy)
}

def checkConnector(connectorName, environment) {
  Boolean bool = true
  withCredentials([usernamePassword(credentialsId: "${env.CONNECTOR_CRED}", usernameVariable: 'username', passwordVariable: 'password')]) {
    //echo sh(script: 'env|sort', returnStdout: true)
    def url = getConnectorClusterURL(environment)
    def command = "curl --basic --user ${username}:${password} --show-error --connect-timeout 600 --max-time 900 --location ${url}/connectors/${connectorName}/status"
    def response = sh(returnStdout:true, script: "${command}")
    println response
    def props = readJSON text: response
    if (props.error_code) {
      bool = false
    }
  }
  return bool
}

def getConnectorClusterURL(environment) {
  def url
  println "environment=>" + environment
  switch(environment.toLowerCase()) {
    case "dev":
      url = env.DEV
      break;
    case "qa":
      url = env.QA
      break;
    case "uat":
      url = env.UAT
      break;
    case "prod":
      url = env.PROD
      break;
    default:
      error("environment is not exist, please fix the environment setting in the Jenkinsfile and try it again!!!!")
      break;
  }
  return url
}

def deleteConnector(connectorName, environment) {
  println "Delete connector -> " + connectorName + " from env -> " + environment
  withCredentials([usernamePassword(credentialsId: "${env.CONNECTOR_CRED}", usernameVariable: 'username', passwordVariable: 'password')]) {
    //echo sh(script: 'env|sort', returnStdout: true)
    def url = getConnectorClusterURL(environment)
    def command = "curl --basic --user ${username}:${password} --show-error -X DELETE --connect-timeout 600 --max-time 900 -H 'Accept: application/json' --location ${url}/connectors/${connectorName}"
    def response = sh(returnStdout:true, script: "${command}")
    println response
  }
}

def createConnector(connectorName, connector, environment, repo, isProdDeploy) {
  println "Create connector -> " + connector + " from env -> " + environment
  //processing the secret
  secretProcess(connector.template, connector.connectorName, connector.connectorName, environment, repo, isProdDeploy)
  //processing the env
  def envMap = [:]
  if (fileExists("${workspace}/${connector.envRoot}/${connector.connectorName}/${environment}.groovy")) {
    echo "Yes, ${workspace}/${connector.envRoot}/${connector.connectorName}/${environment}.groovy exists"
    def tempScript = load "${workspace}/${connector.envRoot}/${connector.connectorName}/${environment}.groovy"
    envMap = tempScript.getEnvMap()
  } else {
    echo "No, ${workspace}/${connector.envRoot}/${connector.connectorName}/${environment}.groovy is NOT EXISTS"
  }
  envMap["connectorName"] = "${connectorName}"
  print envMap
  //process the env template
  def fileContents = readFile("${workspace}/${connector.connectorName}")
  def result = applyEnvMap(fileContents, envMap)
  writeFile file: "${workspace}/${connector.connectorName}", text: result

  //create the connector
  withCredentials([usernamePassword(credentialsId: "${env.CONNECTOR_CRED}", usernameVariable: 'username', passwordVariable: 'password')]) {
    //echo sh(script: 'env|sort', returnStdout: true)
    def url = getConnectorClusterURL(environment)
    def command = "curl --basic --user ${username}:${password} -X POST --connect-timeout 600 --max-time 900 --header 'Content-Type: application/json' --data-binary @${workspace}/${connector.connectorName} --location ${url}/connectors"
    def output = sh(script: "$command", returnStdout: true)
    def props = readJSON text: output
    if (props.error_code) {
      println "Failed to deploy connector: ${connectorName}"
      println props
      error("Failed to deploy connector: ${connectorName}")
    } else {
      println "Successfully deploy connector: ${connectorName} with tasks -> " + props.tasks + " and type -> " + props.type + " ! "
    }
  }

}

def applyEnvMap(text, envMap) {
  envMap.each { k, v ->
    if (v != null && k != 'override') {
      text = text.replaceAll('\\$\\{' + k.toString() + '\\}', v)
    }
  }
  return text
}

def secretProcess(templates, secrets, connectorName, deployEnv, repo, isProd) {
  def commons = new com.aristotlecap.pipeline.Commons()
  try {
    def part = (isProd)? "prod":"nonprod"
    def secretRootBase = "secret/${repo.organization}/${repo.name}/${part}"
    def secretRoot = (isProd)? "${secretRootBase}":"${secretRootBase}/${deployEnv}"

    def appRoleName = "${repo.organization}-${repo.name}-${part}"
    def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, isProd);

    echo "application vault auth token -> ${appVaultAuthToken}"
    commons.templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
  } catch (err) {
    currentBuild.result = 'FAILED'
    throw err
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

def gitTag(message, tagName, branchName) {
  //tagging the master branch
  gitConfigSetup()
  sh """
    git branch
    git checkout ${branchName}
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a ${tagName} -m "${message}"'
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin ${tagName}'
  """
}
