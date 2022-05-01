package com.aristotlecap.pipeline.util

def deployDashboards(deployEnv) {
  if (fileExists("${workspace}/${deployEnv}.yaml")) {
    withCredentials([string(credentialsId: "${env.JENKINS_GRAFANA_AUTH}", variable: "GRAFANA_AUTH")]) {

      def dashboardFileName = "${deployEnv}.yaml"

      //read dashboard yaml config file
      def config = readYaml file: dashboardFileName
      def dashboards = config.wamDashboards

      def me = config.me
      if (me != null) {
        println "me is not null"
      } else {
        println "me is null"
      }

      def server = null
      if (fileExists("${workspace}/conf/env/${deployEnv}.groovy")) {
        print "Yes, ${workspace}/conf/env/${deployEnv}.groovy exists"
        def tempScript = load "${workspace}/conf/env/${deployEnv}.groovy"
        server = tempScript.getEnvMap().GRAFANA_SERVER
      } else {
        print "No, ${workspace}/conf/env/${deployEnv}.groovy is not exists"
      }

      if (dashboards != null) {
        def i = 0
        dashboards.each({ f ->
          def folderId = findFolderId(f.folderName, server)
          print 'folder id ->' + folderId
          def dashboardUid = findDashboardUid(f.title, server)
          print dashboardUid
          if (dashboardUid != null) {
            print 'update the dashboard logic will be here'
            updateDashboard(f.sourceFile, folderId, dashboardUid, server, i)
          } else {
            print 'create the new dashboard logic will be here'
            createNewDashboard(f.sourceFile, folderId, server, i)
          }
          i++
        })
      }
    }
  }
}

def secretProcessing(config, repo, deployEnv, isProdDeploy) {
  def commons = new com.aristotlecap.pipeline.Commons()

  def templateList = []
  def secretList = []
  config.secrets.each { k, v ->
    templateList.push(k)
    secretList.push(v)
  }

  //vault process
  def templatesStr = getJoinList(templateList)
  println templatesStr
  def secretsStr = getJoinList(secretList)
  println secretsStr
  commons.secretProcess(templatesStr, secretsStr, deployEnv, repo.organizationName, repo.appGitRepoName, isProdDeploy)

  def envMap = [:]
  envMap.ORG = "${repo.organizationName}"
  envMap.REPO = "${repo.appGitRepoName}"
  if (isProdDeploy) {
    envMap.REPO_KEY = "${env.IMAGE_REPO_PROD_KEY}"
  } else {
    envMap.REPO_KEY = "${env.IMAGE_REPO_NONPROD_KEY}"
  }
  println envMap
  def secrets = config.secrets
  secrets.each { filename ->
    println 'filename -> ' + filename
    applyEnvMap(filename, envMap)
  }
}

def applyEnvMap(filename, map) {
  def fileContents = readFile("${workspace}/${filename}")
  map.each { k, v ->
    fileContents = fileContents.replaceAll('\\$\\{' + k.toString() + '\\}', v)
  }
  writeFile file: "${workspace}/${filename}", text: fileContents
}

def findFolderId(folderName, server) {
  // list all the folders
  def url = "https://api_key:${GRAFANA_AUTH}@${server}/api/folders"
  String folders = sh(label: 'get folders', returnStdout: true, script: "curl ${url}").trim()
  //print folders
  def jsonObj = readJSON text: folders
  def folderId = null
  jsonObj.each({ it ->
     if (it.title.equalsIgnoreCase(folderName)) {
       folderId = it.id
     }
  })
  return folderId
}

def findDashboardUid(dashboardTitle, server) {
  // list all the dashboard - > type = dash-db
  url = "https://api_key:${GRAFANA_AUTH}@${server}/api/search?query=%"
  String all = sh(label: 'get folders', returnStdout: true, script: "curl ${url}").trim()
  //print all
  def jsonObj = readJSON text: all
  def dashboardUid = null
  jsonObj.each({ item ->
     //print item.type
     //print item.title
     //print dashboardTitle
     if (item.type.equalsIgnoreCase('dash-db') && item.title.equalsIgnoreCase(dashboardTitle)) {
       //print 'find it'
       //print item
       dashboardUid = item.uid
     }
  })
  println 'dashboardUid->' + dashboardUid
  return dashboardUid
}

def createNewDashboard(fileLoc, folderId, server, i) {
  // import a dashboard
  def dashboardJson = readJSON file: fileLoc
  dashboardJson['id'] = null
  dashboardJson['uid'] = null
  dashboardJson['version'] = 1

  def map = [:]
  map['Dashboard']=dashboardJson
  map['folderId']=folderId
  map['overwrte']=false

  def jsonFileName = "${folderId}" + "-" + "${i}" + ".json"
  print jsonFileName

  writeJSON file: "${jsonFileName}", json: map, pretty: 4
  //sh """
  //  pwd
  //  cat ./${jsonFileName}
  //"""

  def json = readJSON file: jsonFileName
  def jsonStr = json.toString()
  //print jsonStr

  def options = "-X POST -H 'Authorization: Bearer $GRAFANA_AUTH' -H 'Content-Type: application/json' -d '$jsonStr' https://$server/api/dashboards/db"
  String ds = sh(label: 'import dashboard', returnStdout: true, script: "curl ${options}").trim()
  print ds

  sh " rm ./${jsonFileName} "
}

def updateDashboard(fileLoc, folderId, dashboardUid, server, i) {
  //load the updated dashboard
  def dashboardJsonNew = readJSON file: fileLoc

  //load the old dashboard
  def options = "-H 'Authorization: Bearer $GRAFANA_AUTH' -H 'Content-Type: application/json' -H 'Accept: application/json' --connect-timeout 600 --max-time 900 https://$server/api/dashboards/uid/$dashboardUid"
  String ds = sh(label: 'get dashboard', returnStdout: true, script: "curl ${options}").trim()
  //print ds

  def jsonObj = readJSON text: ds

  def map = [:]
  dashboardJsonNew['id'] = jsonObj['dashboard']['id']
  dashboardJsonNew['uid'] = jsonObj['dashboard']['uid']
  dashboardJsonNew['version'] = jsonObj['dashboard']['version']
  map['Dashboard']=dashboardJsonNew
  map['folderId']=folderId
  map['overwrte']=true

  def jsonFileName = "${folderId}" + "-" + "${i}" + ".json"
  print jsonFileName

  writeJSON file: "${jsonFileName}", json: map, pretty: 4
  //sh """
  //  pwd
  //  cat ./${jsonFileName}
  //"""

  def json = readJSON file: jsonFileName
  def jsonStr = json.toString()
  //print jsonStr

  def options2 = "-X POST -H 'Authorization: Bearer $GRAFANA_AUTH' -H 'Content-Type: application/json' --connect-timeout 600 --max-time 900 -d '$jsonStr' https://$server/api/dashboards/db"
  String ds2 = sh(label: 'import dashboard', returnStdout: true, script: "curl ${options2}").trim()
  print ds2

  sh " rm ./${jsonFileName} "

}
