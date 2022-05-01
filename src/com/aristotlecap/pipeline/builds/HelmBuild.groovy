package com.aristotlecap.pipeline.builds

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*

def multiVendorDeploy(config, buildNumber, environment, imageTagMap, branchName, repo, isProdDeploy, crNumber) {

  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def vault = new Vault()
  def docker = new Docker()
  def helm = new Helm()
  def kubectl = new Kubectl()
  def aws = new Aws()
  def slack = new Slack()
  def conditionals = new Conditionals()
  def namespace

  String cluster
  String deployEnv

  print imageTagMap

  if (isProdDeploy) {
    deployEnv = 'prod'
    cluster = environment
  } else {
    def envToken = environment.split(':')
    deployEnv = envToken[0]
    cluster = envToken[1]
  }

  currentBuild.displayName = "${branchName}-${config.releaseVersion}-${buildNumber}-${deployEnv}"
  if (isProdDeploy) {
    currentBuild.displayName = "${branchName}-${config.releaseVersion}-${buildNumber}-${crNumber}"
  }

  def sshVolume = (isProdDeploy)? ssh.prodKeysVolume():ssh.keysVolume()

  pod.awsNode(
    cloud: cluster,
    containers: [ helm.containerTemplate(), kubectl.containerTemplate(),
                  vault.containerTemplate(), docker.containerTemplate() ],
    volumes: [ sshVolume, docker.daemonHostPathVolume() ]
  ) {

    if (null == repo) {
      repo=gitScm.checkout()
    } else {
      deleteDir()
      git url: "${repo.scm}", credentialsId: 'ghe-jenkins', branch: "${branchName}"
      sh "git reset --hard ${repo.commit}"
    }

    def secrets = config.secrets
    def processedSecrets
    conditionals.when(secrets != null && secrets.size() > 0) {
      vault.container {
        processedSecrets = vault.processTemplates(repo, deployEnv, secrets)
      }
    }

    def envMap = getEnvMap(repo, imageTagMap, deployEnv)
    for(s in secrets.values()) {
      applyEnvMap(s, envMap)
    }

    //update the helm repo
    updateHelmRepos(config.helmRepos)

    //deploy the charts
    for (chart in config.charts) {
      def chartEnvironment = chart.environment
      if (!chartEnvironment?.trim() || chartEnvironment.equalsIgnoreCase(deployEnv)) {
        def chartName = chart.chartName
        def chartOptions = chart.chartOptions
        namespace = chart.namespace
        def helmChartInstanceName =(chartName?.trim())? chartName : "${repo.organization}-${repo.name}-${deployEnv}"
        def findValueFile = getValueFiles(chart.values)
        def additionalOptions = (chartOptions?.trim())?chartOptions : " ";
        def chartPath = "${workspace}/${chart.chartPath}/"
        def checkChartLocalPathRetrunCode = sh(label: 'Check Helm Chart Local Path', returnStatus: true, script: "test -d ${chartPath}")
        if (checkChartLocalPathRetrunCode > 0) {
          println 'Cannot find the local path, so the chart is from remote repo'
          chartPath = "${chart.chartPath}"
        } else {
          println 'Find the local path, so the chart is from local folder'
          chartPath = "${workspace}/${chart.chartPath}"
        }
        String registry = "/home/jenkins/agent/.config/helm/registry.json"
        String repositoryCache = "/home/jenkins/agent/.cache/helm/repository"
        String repositoryConfig = "home/jenkins/agent/.config/helm/repositories.yaml"
        helm.container {
          sh """
            helm upgrade --install $findValueFile --namespace $namespace --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig $additionalOptions $helmChartInstanceName $chartPath
            sleep 60
          """
        }
      }
    }

    //check if the kuberneteSecrets is not empty
    conditionals.when(null != config.kuberneteSecrets && !config.kuberneteSecrets.isEmpty()) {
      kubectl.container {
        config.kuberneteSecrets.each { secretName, secretFiles ->
          def c = []
          secretFiles.split(',').each { s ->  c.push(s)}
          kubectl.createSecretIfNew(namespace, secretName, c)
        }
      }
    }

    //create a secrets for application
    def appSecretName = "${repo.organization}-${repo.name}-${deployEnv}-secret"
    conditionals.when(null != processedSecrets) {
      kubectl.container {
        kubectl.createSecret(namespace, appSecretName, processedSecrets)
      }
    }

    //process the kubernetes
    if (fileExists("./kubernetes")) {
      kubectl.deploy(deployEnv, repo, imageTagMap)
    }

    //check if it is not maven project so we need to do git release
    if (null == config.builderTag && isProdDeploy) {
      def tagName = "${branchName}-${config.releaseVersion}"
      println "Git tag name ->" + tagName
      commitRelease(tagName)
    }

  }
  return repo
}

def getValueFiles(values) {
  def f = ''
  if (null!=values && values.trim()) {
    def valuesYamls = values.split(',')
    valuesYamls.each{
      f = (f?.trim())? f + "-f ${workspace}/${it} " : "-f ${workspace}/${it} "
    }
  }
  return f
}

def updateHelmRepos(repos) {
  def helm = new Helm()
  String registry = "/home/jenkins/agent/.config/helm/registry.json"
  String repositoryCache = "/home/jenkins/agent/.cache/helm/repository"
  String repositoryConfig = "home/jenkins/agent/.config/helm/repositories.yaml"
  if (repos != null) {
    helm.container {
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

def prodDeploy(config, projectName, buildNumber, environment, imageTagMap, repo) {
  deploy(config, projectName, buildNumber, environment, imageTagMap, null, repo)
}

def nonprodDeploy(config, projectName, buildNumber, environment, imageTagMap, branchName) {
  deploy(config, projectName, buildNumber, environment, imageTagMap, branchName, null)
}

def deploy(config, projectName, buildNumber, environment, imageTagMap, branchName, repo) {

  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def git = new Git()
  def vault = new Vault()
  def docker = new Docker()
  def helm = new Helm()
  def kubectl = new Kubectl()
  def aws = new Aws()
  def slack = new Slack()
  def conditionals = new Conditionals()

  def envToken = environment.split(':')
  String deployEnv = envToken[0]
  String account = envToken[1]
  String eksCluster = aws.getEksCluster(account)

  def sshVolume = (deployEnv == 'prod')? ssh.prodKeysVolume():ssh.keysVolume()

  pod.awsNode(
    cloud: eksCluster,
    containers: [ helm.containerTemplate(), kubectl.containerTemplate(),
                  vault.containerTemplate(), docker.containerTemplate() ],
    volumes: [ sshVolume, docker.daemonHostPathVolume() ]
  ) {

    if (branchName?.trim()||null==repo) {
      repo=gitScm.checkout()
    } else {
      gitScm.checkout(repo)
    }

    println repo

    println config
    println imageTagMap

    String userId
    //find out the requestor and request login will be used as the name for the user branch
    try {
      wrap([$class: 'BuildUser']) {
        userId = "${BUILD_USER_ID}"
      }
    } catch(exp) {
      userId = 'default-user'
    }
    if (!userId?.trim())
      error('Unable to  get User ID')

    print 'user ->' + userId

    for (e in config.kubeApps) {
      if (e.appType.toLowerCase() == 'service') {
        def secrets = e.secrets
        def appBaseDir = e.dockerFile.split('/')[0]
        if (e.enabled) {
          def helmChartName = repo.organization + '-' + repo.getSafeName() + '-' + appBaseDir + '-' + deployEnv
          def namespacename = repo.organization + '-default'

          try {
            conditionals.when(secrets != null && secrets.size() > 0) {
              def processedSecrets
              vault.container {
                processedSecrets = vault.processTemplates(repo, deployEnv, secrets)
              }
              kubectl.container {
                kubectl.createSecret(repo, appBaseDir, deployEnv, processedSecrets)
              }
            }

            def imageTag = imageTagMap[appBaseDir]
            def envMap = getEnvMap(repo, appBaseDir, imageTag, deployEnv)

            def valuesOptions = ""
            findFiles(glob: "${appBaseDir}/helm/*.yaml").each { file ->
              applyEnvMap("${appBaseDir}/helm/" + file.name, envMap)
              sh "cat ${file.path}"
              print file.path
              print file.name
              valuesOptions = valuesOptions + " -f " + file.path
            }
            println valuesOptions

            String registry = "/home/jenkins/agent/.config/helm/registry.json"
            String repositoryCache = "/home/jenkins/agent/.cache/helm/repository"
            String repositoryConfig = "home/jenkins/agent/.config/helm/repositories.yaml"
            String helmChartVersion = e.helmChartVersion

            container('helm') {
              sh """
                helm repo add --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig wam-helm $env.HELM_REPO
                helm repo list --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig
                helm repo update --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig
                helm upgrade --install $valuesOptions --dry-run --namespace $namespacename --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig $helmChartName wam-helm/kube-deploy --version=$helmChartVersion
                helm upgrade --install $valuesOptions --wait --namespace $namespacename --registry-config $registry --repository-cache $repositoryCache --repository-config $repositoryConfig $helmChartName wam-helm/kube-deploy --version=$helmChartVersion
              """
            }
            slack.sendSlackMessage(helmChartName, "REST", repo.branch, buildNumber, userId, repo.commit, deployEnv, null)
          } catch(exception) {
            slack.sendSlackMessage(helmChartName, "REST", repo.branch, buildNumber, userId, repo.commit, deployEnv, exception.getMessage())
            error(exception.getMessage())
          }
        }
      }
    }
  }
}

def getEnvMap(repo, appBaseDir, imageTag, deployEnv) {
  def envMap = [:]

  if (fileExists("${workspace}/${appBaseDir}/conf/env/${deployEnv}.groovy")) {
    echo "Yes, ${workspace}/${appBaseDir}/conf/env/${deployEnv}.groovyy exists"
    def tempScript = load "${workspace}/${appBaseDir}/conf/env/${deployEnv}.groovy"
    envMap = tempScript.getEnvMap()
  }

  envMap.TAG = imageTag
  envMap.ORG = repo.organization
  envMap.REPO = repo.getSafeName()
  envMap.IMAGEHUB = env.IMAGE_REPO_URI
  envMap.ENV = deployEnv
  envMap.REPO_KEY = (deployEnv != 'prod')?env.IMAGE_REPO_NONPROD_KEY:env.IMAGE_REPO_PROD_KEY

  return envMap
}

def getEnvMap(repo, imageTagMap, deployEnv) {
  def envMap = [:]

  if (fileExists("${workspace}/conf/env/${deployEnv}.groovy")) {
    echo "Yes, ${workspace}/conf/env/${deployEnv}.groovyy exists"
    def tempScript = load "${workspace}/conf/env/${deployEnv}.groovy"
    envMap = tempScript.getEnvMap()
  }

  if (null != imageTagMap) {
    imageTagMap.each{ key, value ->
      envMap[key] = value
    }
  }

  envMap.ORG = repo.organization
  envMap.REPO = repo.getSafeName()
  envMap.IMAGEHUB = env.IMAGE_REPO_URI
  envMap.ENV = deployEnv
  envMap.REPO_KEY = (deployEnv != 'prod')?env.IMAGE_REPO_NONPROD_KEY:env.IMAGE_REPO_PROD_KEY

  return envMap
}

def applyEnvMap(filename, map) {
  def fileContents = readFile("${workspace}/${filename}")
  map.each { k, v ->
    if (v != null) {
      fileContents = fileContents.replaceAll('\\$\\{' + k.toString() + '\\}', v)
    }
  }
  writeFile file: "${workspace}/${filename}", text: fileContents
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

return this
