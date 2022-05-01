package com.aristotlecap.pipeline.util

import com.aristotlecap.pipeline.Commons


def getValueFiles(values, deployEnv) {
  def f = ''
  def valuesYamls = values.get(deployEnv).split(',')
  valuesYamls.each{
    f = (f?.trim())? f + "-f ${workspace}/${it} " : "-f ${workspace}/${it} "
  }
  return f
}

def getJoinList(list) {
  def str = "null"
  if (list != null && list.size() > 0) {
     str = list.join("\n")
  }
  return str
}

def applyEnvMap(filename, map) {
  def fileContents = readFile("${workspace}/${filename}")
  map.each { k, v ->
    fileContents = fileContents.replaceAll('\\$\\{' + k.toString() + '\\}', v)
  }
  writeFile file: "${workspace}/${filename}", text: fileContents
}

def chartsToString(charts) {
  def m = [:]
  def n = []
  charts.each{ it ->
    n.push(it)
  }
  m['charts'] = n
  def json = new groovy.json.JsonBuilder()
  json rootKey: m
  return json.toString()
}

def stringToCharts(str) {
  def js = new groovy.json.JsonSlurper()
  def cfg = js.parseText(str)
  def charts = cfg.rootKey.charts
  return charts
}

def updateHelmRepos(config) {
  def repos = config.helmRepos
  String registry = "/home/jenkins/agent/.config/helm/registry.json"
  String repositoryCache = "/home/jenkins/agent/.cache/helm/repository"
  String repositoryConfig = "home/jenkins/agent/.config/helm/repositories.yaml"
  if (repos != null) {
    container('builder') {
      repos.each{ key, value ->
        sh """
          helm repo add --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig $key $value
          helm repo list --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig
          helm repo update --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig
        """
      }
    }
  }
}

def helmDeploy(config, repo, deployEnv, isProdDeploy) {

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
  def dockerfileToTagMap = config.dockerfiles
  dockerfileToTagMap.each{ key, value ->
    def imageTagMap = "${config.branch_name}-${value}-${config.build_number}"
    envMap["${value}"] = "${imageTagMap}"
  }
  println envMap
  def secrets = secretList
  secrets.each { filename ->
    println 'filename -> ' + filename
    applyEnvMap(filename, envMap)
  }

  def mns = "${config.namespace}"
  def namespacename = (mns != 'null')? mns : "${repo.organizationName}-default"

  def charts = config.charts
  for(chart in charts) {
    def chartName = chart.chartName
    def helmChartInstanceName =(chartName?.trim())? chartName : "${repo.organizationName}-${repo.appGitRepoName}-${deployEnv}"
    def findValueFile = getValueFiles(config.values, deployEnv)
    def option = chart.chartOptions
    def additionalOptions = (option?.trim())?chart.chartOptions : " ";
    def chartPath = "${workspace}/${chart.chartPath}/"
    def checkChartLocalPathRetrunCode = sh(label: 'Check Helm Chart Local Path', returnStatus: true, script: "test -d ${chartPath}")
    if (checkChartLocalPathRetrunCode > 0) {
       println 'Cannot find the local path, so the chart is from remote repo'
       chartPath = "${chart.chartPath}"
    } else {
       println 'Find the local path, so the chart is from local folder'
       chartPath = "${workspace}/${chart.chartPath}"
    }

    container('builder') {
      String registry = "/home/jenkins/agent/.config/helm/registry.json"
      String repositoryCache = "/home/jenkins/agent/.cache/helm/repository"
      String repositoryConfig = "home/jenkins/agent/.config/helm/repositories.yaml"
      sh """
        helm upgrade --install $findValueFile --namespace $namespacename --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig $additionalOptions $helmChartInstanceName $chartPath
        sleep 60
      """
    }
  }
}

def pushImageToProdDtr(image, approveImage) {
  container('docker') {
    docker.withRegistry("https://${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_CREDENTIAL}") {
      sh """
        docker pull $image
        docker tag $image $approveImage
        docker push $approveImage
      """
    }
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

def commitRelease(tagName) {
  gitConfigSetup()
  sh """
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $tagName -m $tagName'
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $tagName'
  """
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
    def v =''
    if (mmd.length > 1) {
      v = mmd[1]
    }
    map[k] = v
    i=i+1
  }
  print map
  return map
}

def nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                       organizationName, appGitRepoName, dockerhub, imageTag, secrets,
                       dockerfileToTagMap, crNumber, helmChartVersion) {

    nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                       organizationName, appGitRepoName, dockerhub, imageTag, secrets,
                       dockerfileToTagMap, crNumber, "Deploy to NonProd", helmChartVersion)
}

def nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, deployEnv,
                       organizationName, appGitRepoName, dockerhub, imageTag, secrets,
                       dockerfileToTagMap, crNumber, stageName, helmChartVersion) {
  try {
    //construct the vault secretRoot and appRoleName
    def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
    def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'

    def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
    echo workspace

    stage("${stageName}") {
      deploy(workspace, imageTag, dockerhub, secretRoot, appRoleName,
             organizationName, appGitRepoName, deployEnv, secrets, false,
             false, dockerfileToTagMap, gitBranchName, buildNumber, gitCommit,
             crNumber, helmChartVersion)
    }

  } catch (err) {
    currentBuild.result = 'FAILED'
    throw err
  }
}

def deploy(workspace, deployImageTag, deployImageDtrUri, secretRoot, appRoleName,
           organizationName, appGitRepoName, deployEnv, secrets, isDeployToProd,
           isDeployToDr, dockerfileToTagMap, gitBranchName, buildNumber, gitCommit,
           crNumber, helmChartVersion) {

  echo "${deployImageTag}"
  echo "${secretRoot}"

  echo organizationName
  echo appGitRepoName
  echo deployEnv

  def commons = new com.aristotlecap.pipeline.Commons()

  print secrets

  def keys = secrets.collect { it.key }
  def vals = secrets.collect { it.value }

  println keys
  println vals

  def templatesStr = getJoinList(keys)
  println templatesStr
  def secretsStr = getJoinList(vals)
  println secretsStr

  if (secrets != null) {
    def appVaultAuthToken = commons.generateVaultAuthToken(appRoleName, isDeployToProd);
    echo "application vault auth token -> ${appVaultAuthToken}"

    //echo sh(script: 'env|sort', returnStdout: true)

    def secretRootBase = "secret/${organizationName}/${appGitRepoName}/nonprod"
    if (isDeployToProd) {
      secretRootBase = "secret/${organizationName}/${appGitRepoName}/prod"
    }

    commons.templateProcessing(templatesStr, secretsStr, secretRoot, secretRootBase, appVaultAuthToken)
    commons.createKebernetesSecret(secretsStr, organizationName, appGitRepoName, deployEnv, isDeployToProd)
  }

  lock(label: "pas_deploy")  {

    def envMap = [:]
    def myEnv = deployEnv
    if (isDeployToProd) {
      myEnv = 'prod'
    }

    //if try to deploy to DR, we need check if the dr.groovy is exist in env folder, if so load it, otherwise load the prod.groovy
    if (isDeployToDr) {
      if (fileExists("${workspace}/conf/env/dr.groovy")) {
        myEnv = 'dr'
      }
    }

    if (fileExists("${workspace}/conf/env/${myEnv}.groovy")) {
      echo "Yes, ${workspace}/conf/env/${myEnv}.groovy exists"
      def tempScript = load "${workspace}/conf/env/${myEnv}.groovy"
      envMap = tempScript.getEnvMap()
    }

    def repoR = appGitRepoName.replace('.', '-').toLowerCase()
    envMap.TAG = "${deployImageTag}"
    envMap.ORG = "${organizationName}"
    envMap.REPO = "${repoR}"
    envMap.IMAGEHUB = "${env.IMAGE_REPO_URI}"
    envMap.SPLUNK_TAG = "${env.SPLUNK_TAG}"

    //it should be prod for both prod & dr
    if (isDeployToProd) {
      envMap.ENV = "prod"
    } else {
      envMap.ENV = "${myEnv}"
    }

    if (isDeployToProd) {
      envMap.REPO_KEY = "${env.IMAGE_REPO_PROD_KEY}"
    } else {
      envMap.REPO_KEY = "${env.IMAGE_REPO_NONPROD_KEY}"
    }

    if (dockerfileToTagMap != null) {
      def dkrfToTagMap = getMapFromString(dockerfileToTagMap)

      echo "${dkrfToTagMap}"
      dkrfToTagMap.each{ key, value ->
        imageTagMap = "${gitBranchName}-${value}-${buildNumber}"
        echo "${imageTagMap}"
        envMap["${value}"] = "${imageTagMap}"
      }
    }

    println envMap

    def valuesOptions = ""
    findFiles(glob: 'kubernetes/*.yaml').each { file ->
        applyEnvMap('kubernetes/' + file.name, envMap)
        sh "cat ${file.path}"
        print file.path
        print file.name
        valuesOptions = valuesOptions + " -f " + file.path
    }
    println valuesOptions

    def helmChartName = organizationName + '-' + appGitRepoName + '-' + deployEnv
    if (isDeployToProd) {
      helmChartName = organizationName + '-' + appGitRepoName + '-prod'
    }

    def namespacename = organizationName + '-default'

    String registry = "/home/jenkins/agent/.config/helm/registry.json"
    String repositoryCache = "/home/jenkins/agent/.cache/helm/repository"
    String repositoryConfig = "home/jenkins/agent/.config/helm/repositories.yaml"

    container('helm') {
      sh """
        helm repo add --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig wam-helm $env.HELM_REPO
        helm repo list --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig
        helm repo update --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig
        helm upgrade --install $valuesOptions --dry-run --namespace $namespacename --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig $helmChartName wam-helm/kube-deploy --version=$helmChartVersion
        helm upgrade --install $valuesOptions --wait --namespace $namespacename --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig $helmChartName wam-helm/kube-deploy --version=$helmChartVersion
      """
    }

  }
  commons.deleteSecretFiles(secretsStr)
}
