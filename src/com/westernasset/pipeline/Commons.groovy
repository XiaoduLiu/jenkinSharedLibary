package com.westernasset.pipeline;

import groovy.text.*
import java.util.regex.Matcher


def processSAMTemplate(profileName) {
  if (fileExists("${workspace}/conf/${profileName}.groovy")) {
    echo "Yes, ${workspace}/conf/${profileName}.groovy exists"
    def tempScript = load "${workspace}/conf/${profileName}.groovy"
    envMap = tempScript.getEnvMap()
    if(fileExists("${workspace}/template.yaml")) {
      def fileContents = readFile("${workspace}/template.yaml")
      def result = applyEnvMap(fileContents, envMap)
      println fileContents
      writeFile file: "${workspace}/template.yml", text: result
      sh """
        cat $workspace/template.yml
      """
    } else {
      error "template.yaml not exist!!!!"
    }
  } else {
    echo "No, ${workspace}/conf/${profileName}.groovy does not exist"
  }
  return "template.yml"
}

def getWorkspace() {
  return sh(returnStdout: true, script: "printenv WORKSPACE").trim()
}

def getNonProdEnvDetailsForService(deployEnvironment) {
  def tokens = deployEnvironment.split(':')
  def deployEnv = tokens[0]
  def clusterName = (tokens.size()>1)? tokens[1]: null
  return [
     deployEnv: deployEnv,
     clusterName: clusterName
  ]
}

def getNonProdEnvDetailsForBatch(nonprodEnvsStr) {
  def tokens = nonprodEnvsStr.split("\n")
  def map = [:]
  tokens.each { env ->
    def detailsMap = getNonProdEnvDetailsForService(env)
    def deployEnv = detailsMap.deployEnv
    def clusterName = (detailsMap.clusterName!=null)?detailsMap.clusterName:'pas-development'
    def envList = (map[clusterName] != null)?map[clusterName]: []
    envList.add(deployEnv)
    map[clusterName] = envList
  }
  def map2 = [:]
  map.each {
    map2[it.key] = it.value.join("\n")
  }
  return map2
}

def getQaEnv(qaEnvs) {
  def qa = []
  if (qaEnvs != null) {
    qaEnvs.each { v ->
      def qaEnv = getNonProdEnvDetailsForService(v)
      qa.push(qaEnv.deployEnv)
    }
  }
  return qa
}

def getProdCluster(String env) {
  if (env == 'pasx') {
    return 'pas-production'
  } else if (env == 'scx') {
    return 'sc-production'
  } else {
    return env
  }
}

def clone(def mixCaseRepo = 'false') {
  stage ('Clone') {
    def repoUtil = new com.westernasset.pipeline.util.RepoUtil()

    deleteDir()
    checkout scm

    String gitCommit = sh(label: 'Get Git commit', returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
    String gitRemoteURL = sh(label: 'Get Git remote URL', returnStdout: true, script: "git config --get remote.origin.url").trim()
    String gitScm = "git@github.westernasset.com:" + gitRemoteURL.drop(32)
    String shortName = gitRemoteURL.drop(32).reverse().drop(4).reverse()

    def names = shortName.split('/')

    organizationName = names[0]
    appGitRepoName = names[1]

    String appDtrRepo = repoUtil.clean(names[0] + '/' + names[1], (mixCaseRepo == 'yes' || mixCaseRepo == 'true'))

    return [
      gitCommit: gitCommit,
      gitScm: gitScm,
      organizationName: names[0],
      appGitRepoName: names[1],
      appDtrRepo: appDtrRepo
    ]
  }
}

def mavenSnapshotBuild() {
 //echo sh(script: 'env|sort', returnStdout: true)
 container('maven') {
   stage ('Maven Build') {
     echo "EXECUTE MAVEN SNAPSHOT BUILD"
       sh """
         export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=1024m -Xss320m'
         mvn help:effective-settings
         mvn -U -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true clean deploy
         dir
      """
    }
  }
}

Map<String, String> standardLabels(organizationName, repoName, branchName, gitCommit) {
  Map<String, String> labels = [:]

  labels['com.westernasset.github.org'] = organizationName.toString()
  labels['com.westernasset.github.repo'] = repoName.toString()
  labels['com.westernasset.github.branch'] = branchName.toString()
  labels['com.westernasset.github.hash'] = gitCommit.toString()

  def timestamp = new Date().format("yyyy-MM-dd HH:mm:ss'('zzz')'")
  labels['com.westernasset.buildTime'] = "'${timestamp.toString()}'"

  return labels
}

String toCmdLineArgsFromLabels(Map<String, String> labels) {
  def pairs = labels.collect { label -> "--label ${label.key}=${label.value}" }
  return pairs.join(' ')
}

def dockerBuild(imageDtrUri, imageRepo, appDtrRepo, appDtrTag, organizationName, appGitRepoName, branchName, gitCommit, providedDockerfile, stageName, def clusterName="pas-development") {
  println "Inside dockerBuild clusterName = " + clusterName
  def dockerBuildOptions = ""
  if (clusterName.toLowerCase().contains('eks')) {
    dockerBuildOptions = " --network=host "
  }
  container('docker') {
    stage ("${stageName}") {
      docker.withRegistry("https://${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_CREDENTIAL}") {
        echo "EXECUTE DOCKER BUILD & PUSH TO Artifactory"
        def image = "${appDtrRepo}:${appDtrTag}"
        def pasImage = "${imageDtrUri}/${imageRepo}/${appDtrRepo}:${appDtrTag}"
        def branchVersionPart = findBrachAndVersion(appDtrTag)
        def pasImageLatest = "${imageDtrUri}/${imageRepo}/${appDtrRepo}:${branchVersionPart}-latest"
        def labelsArgs = toCmdLineArgsFromLabels(standardLabels(organizationName, appGitRepoName, branchName, gitCommit))

        def dockerfilePath = '.'
        def dockerfileName = 'Dockerfile'
        boolean createLatestTag = false

        if (providedDockerfile != null && providedDockerfile != 'null') {
          dockerfilePath = findPath(providedDockerfile)
          dockerfileName = providedDockerfile
          createLatestTag = true
        }

        removeTag(pasImage)
        removeTag(image)

        sh(label: "Build and push Docker image", script: """
            docker image build $dockerBuildOptions --tag $image -f $dockerfileName $labelsArgs $dockerfilePath
            docker tag $image $pasImage
            docker push $pasImage
        """)

        if (createLatestTag) {
          removeTag(pasImageLatest)

          sh(label: "Create and push latest tag", script: """
            docker tag $image $pasImageLatest
            docker push $pasImageLatest
          """)
        }
      }
    }
  }
}

def dockerBuildForMultiImages(imageDtrUri, imageRepo, appDtrRepo, appDtrTag, organizationName, appGitRepoName, branchName, gitCommit, providedDockerfile, buildNumber, stageName) {
  container('docker') {
    stage ("${stageName}") {
      appDtrTag = "${branchName}-${appDtrTag}-${buildNumber}"
      docker.withRegistry("https://${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_CREDENTIAL}") {
        echo "EXECUTE DOCKER BUILD & PUSH TO Artifactory"
        def image = "${appDtrRepo}:${appDtrTag}"
        def pasImage = "${imageDtrUri}/${imageRepo}/${appDtrRepo}:${appDtrTag}"
        def nowTimeStamp = new Date().format("yyyy-MM-dd HH:mm:ss'('zzz')'")
        removeTag(image)
        removeTag(pasImage)
        def dockerfilepath = findPath(providedDockerfile)
        echo "dockerfilepath -> ${dockerfilepath}"
        sh """
          cd $dockerfilepath
          docker image build --tag $image -f $providedDockerfile --label com.westernasset.github.org=$organizationName  --label com.westernasset.github.repo=$appGitRepoName --label com.westernasset.github.branch=$branchName --label com.westernasset.github.hash=$gitCommit --label com.westernasset.buildTime='$nowTimeStamp' .
          docker tag $image $pasImage
          docker push $pasImage
        """
      }
    }
  }
}

def removeTag(tag) {
  return sh(
    label: "Remove Docker image if it exists",
    script: "docker image rm $tag",
    returnStatus: true
  ) == 0 ? true : false
}

def liquibaseProcess(liquibaseProjectFolder, workSpaceDir, liquibaseChange, secretRoot, appRoleName,
                     isProd, liquibaseRollbackTag, projectType) {
  echo "EXECUTE Liquibase step"

  //get the app vault auth token
  def appVaultAuthToken = generateVaultAuthToken(appRoleName, isProd);
  //echo "application vault auth token -> ${appVaultAuthToken}"

  def liquibaseRootFolder = "${liquibaseProjectFolder}"

  echo workSpaceDir
  echo 'liquibaseRootFolder ->' + liquibaseRootFolder

  echo "EXECUTE LIQUIBASE CHANGE"

  //get liquibase.properties
  copyLiquibaseTemplate()

  container('vault') {
    withEnv(["VAULT_TOKEN=${appVaultAuthToken}", "SECRET_ROOT=${secretRoot}"]) {
      sh """
        consul-template -vault-renew-token=false -once -template $WORKSPACE/wam_liquibase.ctmpl:$WORKSPACE/liquibase.properties
      """
    }
  }
  //get base location
  //sh 'printenv'
  def baseLocation = (liquibaseRootFolder != 'null')? "${WORKSPACE}/${liquibaseRootFolder}" : "${WORKSPACE}"
  print baseLocation

  container('liquibase') {
    if(projectType == 'liquibase') {
      sh """
        cd $baseLocation
        /opt/resources/liquibase/liquibase --logLevel=DEBUG --defaultsFile=$WORKSPACE/liquibase.properties --changeLogFile=./$liquibaseChange tag $liquibaseRollbackTag
      """
    }
    sh """
      cd $baseLocation
      /opt/resources/liquibase/liquibase --logLevel=DEBUG --defaultsFile=$WORKSPACE/liquibase.properties --changeLogFile=./$liquibaseChange update
    """
  }

}

def copyLiquibaseTemplate() {
  container('liquibase') {
    def foundFile = sh(script: "ls -1 /opt/resources/liquibase | grep wam_liquibase.ctmpl", returnStatus: true)
    if(foundFile == 0) {
      sh """
        cp /opt/resources/liquibase/wam_liquibase.ctmpl $WORKSPACE/wam_liquibase.ctmpl
      """
    }
  }
  if(!fileExists("${WORKSPACE}/wam_liquibase.ctmpl")) {
    container('vault') {
      sh """
        cp /opt/resources/liquibase/wam_liquibase.ctmpl $WORKSPACE/wam_liquibase.ctmpl
      """
    }
  }
}

def liquibaseRollbackProcess(workSpaceDir, liquibaseChange, liquibaseBuilderTag,
                             secretRoot, appRoleName, liquibaseRollbackTag, isProd) {

  echo "EXECUTE Liquibase Rollback step"

  //get the app vault auth token
  def appVaultAuthToken = generateVaultAuthToken(appRoleName, isProd);
  //echo "application vault auth token -> ${appVaultAuthToken}"

  //echo sh(script: 'env|sort', returnStdout: true)
  echo "EXECUTE LIQUIBASE CHANGE"

  //get liquibase.properties
  copyLiquibaseTemplate()

  container('vault') {
    withEnv(["VAULT_TOKEN=${appVaultAuthToken}", "SECRET_ROOT=${secretRoot}"]) {
      sh """
        consul-template -vault-renew-token=false -once -template  $WORKSPACE/wam_liquibase.ctmpl:$workSpaceDir/liquibase.properties
      """
    }
  }
  container('liquibase') {
    sh """
      /opt/resources/liquibase/liquibase --logLevel=DEBUG --defaultsFile="./liquibase.properties" --changeLogFile=./$liquibaseChange rollback $liquibaseRollbackTag
    """
  }
}

def deploy(workspace, deployImageTag, deployImageDtrUri, secretRoot, appRoleName,
           organizationName, appGitRepoName, environment, templates, secrets,
           isDeployToProd, isDeployToDr, dockerfileToTagMap, gitBranchName, buildNumber,
           gitCommit, crNumber) {

  def returnString

  echo "${workspace}/docker-compose.yml"
  //sh "cat ${workspace}/docker-compose.yml"
  sh "pwd"

  echo "${deployImageTag}"
  echo "${secretRoot}"

  echo organizationName
  echo appGitRepoName
  echo environment

  if (secrets != 'null') {
    def appVaultAuthToken = generateVaultAuthToken(appRoleName, isDeployToProd);
    echo "application vault auth token -> ${appVaultAuthToken}"

    //echo sh(script: 'env|sort', returnStdout: true)

    def secretRootBase = "secret/${organizationName}/${appGitRepoName}/nonprod"
    if (isDeployToProd) {
      secretRootBase = "secret/${organizationName}/${appGitRepoName}/prod"
    }

    templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
    createKebernetesSecret(secrets, organizationName, appGitRepoName, environment, isDeployToProd)
  }

  lock(label: "pas_deploy")  {

    def envMap = [:]
    def myEnv = environment
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

    //deploy the yaml file in this folder
    returnString = processKubernetesYamlFile(isDeployToDr, envMap, workspace, organizationName, appGitRepoName);

    //add annotation to the deployment: crNumber + gitCommit
    //addAnnotationToDeployment(isDeployToDr, returnString, organizationName, gitCommit, crNumber)
  }

  deleteSecretFiles(secrets)
  return returnString
}

def addAnnotationToDeployment(isDeployToDr, deployment, organizationName, gitCommit, crNumber) {
  if (!isDeployToDr) {
    def tokens = deployment.split('/')
    def deploymentName = tokens[1]
    echo 'deployment ->' + deploymentName

    container('kubectl') {
      if (crNumber) {
        sh """
          kubectl annotate --overwrite deployment.extensions/$deploymentName kubernetes.io/gitCommit=$gitCommit kubernetes.io/crNumber=$crNumber --record -n $organizationName-default
        """
      } else {
        sh """
          kubectl annotate --overwrite deployment.extensions/$deploymentName kubernetes.io/gitCommit=$gitCommit --record -n $organizationName-default
        """
      }
    }
  }
}

def findDeploymentFileName() {
  def foundFiles = sh(script: "ls -1 ${workspace}/kubernetes/*.yaml", returnStdout: true).split()
  def dem = foundFiles.length
  def i = 0
  def deploymentFileName
  while (i < dem) {
    echo "i=" + i
    def findFileName = foundFiles[i]
    echo "find yaml fileFullPath -> " + findFileName
    def fileName = findArtifact(findFileName)
    echo "find batch script fileName -> " + fileName
    def fileContents = readFile("${workspace}/kubernetes/${fileName}")
    def result = fileContents
    if (result.contains('Deployment')) {
      deploymentFileName = fileName
    }
    i = i + 1
  }
  return deploymentFileName
}

def applyEnvMap(text, envMap) {
  envMap.each { k, v ->
    text = text.replaceAll('\\$\\{' + k.toString() + '\\}', Matcher.quoteReplacement(v))
  }
  return text
}

def processKubernetesYamlFile(isDeployToDr, envMap, workspace, organizationName, appGitRepoName) {
  def returnString = ''
  def foundFiles = null
  try {
    foundFiles = sh(script: "ls -1 ${workspace}/kubernetes/*.yaml", returnStdout: true).split()
  } catch(exp) {
    println exp.getMessage()
  }
  if (foundFiles != null) {
    def dem = foundFiles.length
    def i = 0
    def namespacename = "${organizationName}-default"
    //process the pod-preset first
    while (i < dem) {
      echo "i=" + i
      def findFileName = foundFiles[i]
      echo "find yaml fileFullPath -> " + findFileName
      def fileName = findArtifact(findFileName)
      echo "find batch script fileName -> " + fileName
      def fileContents = readFile("${workspace}/kubernetes/${fileName}")
      def result = fileContents
      if (result.contains('PodPreset')) {
        result = applyEnvMap(result, envMap)
        findPodPresetNameAndDeleteIt(result, namespacename)

        writeFile file: "${workspace}/${fileName}", text: result
        sh """
          cat $workspace/$fileName
        """
        container('kubectl') {
          sh """
            kubectl apply -f $workspace/$fileName
          """
        }
      }
      i=i+1
    }
    //then process the rest
    i = 0
    def isDeployment = false
    while (i < dem) {
      echo "i=" + i
      def findFileName = foundFiles[i]
      echo "find yaml fileFullPath -> " + findFileName
      def fileName = findArtifact(findFileName)
      echo "find batch script fileName -> " + fileName
      def fileContents = readFile("${workspace}/kubernetes/${fileName}")
      def result = fileContents

      if (!result.contains('PodPreset')) {
        if (result.contains('Deployment')||result.contains('StatefulSet')) {
          isDeployment = true
        } else {
          isDeployment = false
        }

        result = applyEnvMap(result, envMap)

        writeFile file: "${workspace}/${fileName}", text: result
        if (isDeployment) {
          if (!isDeployToDr) {
            container('kubectl') {
              returnString=sh(returnStdout: true, script: "kubectl apply -f $workspace/$fileName").trim()
              echo "${returnString}"
              def tokens = returnString.split()
              returnString = tokens[0]
              echo "${returnString}"
            }
            sh """
              mkdir -p $workspace/$organizationName/$appGitRepoName
              cp $workspace/$fileName $workspace/$organizationName/$appGitRepoName/$fileName
              cat $workspace/$organizationName/$appGitRepoName/$fileName
            """
            def appArt = "${organizationName}/${appGitRepoName}/${fileName}"
            archive(appArt)
          }
        } else {
          sh """
            cat $workspace/$fileName
          """
          container('kubectl') {
            sh """
              kubectl apply -f $workspace/$fileName
            """
          }
        }
      }
      i=i+1
    }
  }
  return returnString
}

def findPodPresetNameAndDeleteIt(str, namespacename) {
  def tokens = str.split('\n')
  def dem = tokens.length
  def i = 0
  while (i < dem) {
    //echo "i=" + i
    def section = tokens[i]
    //echo "section = " + section
    def breaks = section.split(':')
    if (breaks.length >=2) {
      def nameString = breaks[0]
      def valueString = breaks[1]
      //println nameString + ' | ' + valueString
      //println '|' + nameString.trim() + '|'
      //println nameString.trim().equalsIgnoreCase('name')
      //println nameString.trim()=='name'
      if (nameString.trim()=='name')  {
        echo 'find the podpreset name = ' + valueString
        deletePodPreset(valueString.trim(), namespacename)
      }
    }
    i=i+1
    //echo "count =" + i
  }
}

def deletePodPreset(presetName, namespace) {
  presetName = presetName.trim()
  container('kubectl') {
    sh(label: 'Delete podpreset if it exists', returnStatus: true, script: """
      kubectl get podpreset $presetName -n $namespace && \
      kubectl delete podpreset $presetName -n $namespace
    """)
  }
}

def createKebernetesSecret(secrets, org, repo, env, isDeployToProd) {
  def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
  echo 'createKebernetesSecret ->' + workspace
  if (secrets != 'null') {
    container('kubectl') {
      def fa = secrets.split()
      def dem = fa.length
      def i = 0
      def fromFileString = ''
      while (i < dem) {
        echo "i=" + i
        def secretName = fa[i]
        //echo "starting create secret -> " + secretName
        fromFileString = fromFileString + " --from-file=${workspace}/${secretName}"
        i=i+1
        //echo "count =" + i
      }
      //println 'fromFileString=' + fromFileString

      def repoRep = repo.replace('.', '-').toLowerCase()
      def kSecretName = "${org}-${repoRep}-${env}-secret"
      if (isDeployToProd) {
        kSecretName = "${org}-${repoRep}-prod-secret"
      }

      try {
        sh(returnStdout: true, script: "kubectl describe secret -n $org-default $kSecretName").trim()
        sh """
          kubectl delete secret -n $org-default $kSecretName
        """
      } catch (exp) {
        println "${kSecretName} is not found in ${org}-default namespace"
      }

      sh """
        kubectl create secret generic -n $org-default $kSecretName $fromFileString
      """
    }
  }
}

def templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken) {
  def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
  echo workspace
  if (templates != 'null') {
    container('vault') {
      withEnv(["VAULT_TOKEN=${appVaultAuthToken}",
               "SECRET_ROOT=${secretRoot}",
               "SECRET_ROOT_BASE=${secretRootBase}"]) {

        //echo sh(script: 'env|sort', returnStdout: true)

        def ta = templates.split()
        def fa = secrets.split()

        def dem = ta.length
        def i = 0
        while (i < dem) {
          echo "i=" + i
          def templatePath = ta[i]
          //echo "templatePath = " + templatePath

          def configPath = fa[i]
          //echo "configPath = " + configPath

          def secretName = fa[i]
          //echo "starting create secret -> " + secretName

          //echo "secretRoot -> " + secretRoot

          sh """
            consul-template -vault-renew-token=false -once -template $workspace/$templatePath:$workspace/$secretName
          """

          i=i+1
          echo "count =" + i
        }
      }
    }
  }
}

def copySecretsToLocationForCDK(templates, secrets) {
  def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
  echo "${templates}"
  echo "${secrets}"
  if (templates != 'null') {
    def ta = templates.split('\n')
    def fa = secrets.split('\n')

    def dem = ta.length
    def i = 0
    while (i < dem) {
      echo "i=" + i
      def templatePath = ta[i]
      echo "templatePath = " + templatePath

      def secretPath = findPath(templatePath)
      def secretName = fa[i]

      echo 'secretName = '  + secretName
      echo "from ${workspace}/${secretName} to ${workspace}/${secretPath}/${secretName}"

      sh """
        cp $workspace/$secretName $workspace/$secretPath/$secretName
        cat $workspace/$secretPath/$secretName
      """

      i=i+1
      echo "count =" + i
    }
  }
}

def deleteSecretFiles(secrets) {
  def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
  echo workspace
  if (secrets != 'null') {
    def fa = secrets.split()

    def dem = fa.length
    def i = 0
    while (i < dem) {
      echo "i=" + i
      def secretName = fa[i]
      echo "delete secret file -> " + secretName
      sh """
        rm -f $workspace/$secretName
      """
      i=i+1
      echo "count =" + i
    }
  }
}

def batchRun(workspace, deployImageTag, deployImageDtrUri, secretRoot, appRoleName, dockerParameters, appArguments, organizationName, appGitRepoName) {
  sh "pwd"

  echo "${deployImageTag}"
  echo "${secretRoot}"

  def appVaultAuthToken = generateVaultAuthToken(appRoleName, false);
  echo "application vault auth token -> ${appVaultAuthToken}"

  withEnv(["TAG=${deployImageTag}",
           "DTR=${deployImageDtrUri}",
           "VAULT_TOKEN=${appVaultAuthToken}",
           "SECRET_ROOT=${secretRoot}"]) {
    sh """
      docker container run $dockerParameters $deployImageDtrUri/$organizationName/$appGitRepoName:$deployImageTag $appArguments
    """
  }
}

def mavenReleaseBuild(gitBranchName, manualReleaseBranchName) {
  container('maven') {
    sh 'pwd'
    //sh 'who'
    //sh 'ls -la /home/jenkins'
    //sh 'ls -la /opt/resources'
    //sh 'set'

    try {
      echo "EXECUTE MAVEN RELEASE BUILD"
      sh """
        git config --global user.email "jenkins@westernasset.com"
        git config --global user.name "Jenkins Agent"
        git config --global http.sslVerify false
        git config --global push.default matching
        git config -l
        export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=1024m -Xss320m'
        mvn --batch-mode -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true release:prepare -DdryRun=true
        mvn --batch-mode -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true release:clean release:prepare
        mvn --batch-mode -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true release:perform
      """
    } catch (Exception e) {
      def removeBranchName = ":${manualReleaseBranchName}"
      echo "removeBranchName = ${removeBranchName}"
      println e.getMessage()
      sh """
        git config --global user.email "jenkins@westernasset.com"
        git config --global user.name "Jenkins Agent"
        git config --global http.sslVerify false
        git config --global push.default matching
        git config -l
        git checkout $gitBranchName
        git branch -D $manualReleaseBranchName
        ssh-agent sh -c 'ssh-add /home/jenkins/.ssh/ghe-jenkins; git push origin $removeBranchName'
      """
      throw e
    }
  }
}

def snapshotSiteDeploy(gitBranchName) {
  container('maven') {
    echo "EXECUTE MAVEN SITE-DEPLOY"
    sh 'mvn --batch-mode clean install site-deploy'
    try {
      if (gitBranchName == 'master') {
        sh """
          export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=1024m -Xss320m'
          mvn org.sonarsource.scanner.maven:sonar-maven-plugin:3.5.0.1254:sonar
        """
      } else {
        sh """
          mvn -Dsonar.branch.name=$gitBranchName org.sonarsource.scanner.maven:sonar-maven-plugin:3.5.0.1254:sonar
        """
      }
    } catch(Exception e) {
      echo e.getMessage()
    }
  }
}

def sonarProcess(gitBranchName) {
  if (env.TOOL_SONAR_SCANNER != null) {
    stage ('Sonar Scan') {
      def exists = fileExists 'sonar-project.properties'
      if (exists) {
        echo 'sonar-project.properties exist, ready to be scanned'
        container('sonar') {
          try {
            sh """
              ls -a /opt
              ls -la /opt/sonarscanner
              ls -la /opt/sonarscanner/bin
              ls -la /opt/sonarscanner/conf
            """
            if (gitBranchName == 'master') {
              sh """
                /opt/sonarscanner/bin/sonar-scanner -Dsonar.sources=.
              """
            } else {
              sh """
                /opt/sonarscanner/bin/sonar-scanner -Dsonar.branch.name=$gitBranchName -Dsonar.sources=.
              """
            }
          } catch(Exception e) {
            echo e.getMessage()
          }
        }
      } else {
        echo 'sonar-project.properties does not exist, not ready to be scanned!!!'
      }
    }
  } else {
    echo 'env.TOOL_SONAR_SCANNER is not defined, so there is no sonar scan!!!'
  }
}

def generateVaultAuthToken(roleName, isProd) {
  def appAuthToken
  def roleIdCredId = 'nonprod-role-id'
  def tokenCredId = "${env.JENKINS_VAULT_TOKEN}"
  if (isProd) {
    roleIdCredId = 'prod-role-id'
  }

  container('vault') {
    withCredentials([string(credentialsId: "${tokenCredId}", variable: 'VAULT_TOKEN'),
                     string(credentialsId: "${roleIdCredId}", variable: 'ROLE_ID')]) {
      def secretId = sh(script: "vault write -field=secret_id -f auth/approle/role/${roleName}/secret-id", returnStdout: true)
      echo "secretId ->${secretId}"
      appAuthToken = sh(script: "vault write -field=token auth/approle/login role_id=${ROLE_ID} secret_id=${secretId}", returnStdout: true)
    }
  }

  return appAuthToken
}

def releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs) {
  def flag = false
  if (releaseFlag) {
    def qas = qaEnvs.split()
    def dem = qas.length
    def i = 0
    while (i < dem) {
      echo "i=" + i
      def qa = qas[i]
      echo "qa = " + qa

      if (qa == deployEnv) {
        flag = true
      }

      i=i+1
      echo "count =" + i
    }
  }
  return flag
}

def setJobLabelJavaProject(branchName, buildNumber) {
  def pom = readMavenPom file: 'pom.xml'
  print pom
  print pom.version

  print pom.name
  print pom.artifactId

  def pomversion = pom.version

  if (pomversion == null) {
    echo "Project version is null"
    pomversion = pom.parent.version
    echo "Parent pom version ----> ${pomversion}"
  }

  sh 'pwd'

  def imageTag = "${branchName}-${pomversion}-${buildNumber}"
  currentBuild.displayName = imageTag
  return imageTag
}

def setJobLabelNonJavaProject(branchName, gitCommit, buildNumber, releaseVersion) {
  def imageTag = "${branchName}-${gitCommit}-${buildNumber}"
  if (releaseVersion != 'null') {
    imageTag = "${branchName}-${releaseVersion}-${buildNumber}"
  }
  currentBuild.displayName = imageTag
  return imageTag
}

def nonProdBatchRunLogic(gitScm, gitBranchName, gitCommit, buildNumber, userInput,
                     organizationName, appGitRepoName, liquibaseChangeParam, pasDtrUri, pasBuilder,
                     liquibaseProjectFolder, liquibaseBuilderTag, imageTag, dockerParameters, appArguments) {
  try {
    def stackName = organizationName + '-' + appGitRepoName + '-' + userInput
    echo "stackName = " + stackName

    def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${userInput}"
    def liquibaseChange = "${liquibaseChangeParam}"

    def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'

    def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
    echo workspace

    if (liquibaseChange != 'null') {
      //do liquibase step
      stage("Liquibase Database Update") {
        echo "EXECUTE LIQUIDBASE"
        echo "${env.WORKSPACE}"
        liquibaseProcess(liquibaseProjectFolder, workspace, liquibaseChange,
                         secretRoot, appRoleName, false, null, "non-prod")

      }
    }
    stage("Deploy to DEV") {
      echo "EXECUTE DEV DEPLOY"
      batchRun(workspace, imageTag, pasDtrUri, secretRoot, appRoleName,
               dockerParameters, appArguments, organizationName, appGitRepoName)
    }
  } catch (err) {
    currentBuild.result = 'FAILED'
    throw err
  }
}

def nonProdDeployLogicE2E(gitScm, gitBranchName, gitCommit, buildNumber, userInput,
                       organizationName, appGitRepoName, liquibaseChangeParam, dockerhub, liquibaseProjectFolder,
                       imageTag, templates, secrets, dockerfileToTagMap, crNumber) {
  def deploymentString
  try {
    def stackName = organizationName + '-' + appGitRepoName + '-' + userInput
    echo "default stackName = " + stackName
    def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${userInput}"
    def liquibaseChange = "${liquibaseChangeParam}"

    def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'

    def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
    echo workspace

    if (liquibaseChange != 'null') {
      //do liquibase step
      stage("Liquibase Database Update") {
        echo "EXECUTE LIQUIDBASE"
        echo "${env.WORKSPACE}"
        liquibaseProcess(liquibaseProjectFolder, workspace, liquibaseChange,
                         secretRoot, appRoleName, false, 'null', 'non-prod')
      }
    }

    stage("Deploy to E2E") {
      echo "EXECUTE DEV DEPLOY"
      deploymentString = deploy(workspace, imageTag, dockerhub, secretRoot, appRoleName,
                                organizationName, appGitRepoName, userInput, templates, secrets,
                                false, false, dockerfileToTagMap, gitBranchName, buildNumber,
                                gitCommit, crNumber)
    }

  } catch (err) {
    currentBuild.result = 'FAILED'
    throw err
  }
  return deploymentString
}

def nonProdDeployLogic(gitScm, gitBranchName, gitCommit, buildNumber, userInput,
                       organizationName, appGitRepoName, liquibaseChangeParam, dockerhub, liquibaseProjectFolder,
                       imageTag, templates, secrets, dockerfileToTagMap, crNumber) {
  def deploymentString
  try {
    def stackName = organizationName + '-' + appGitRepoName + '-' + userInput
    echo "default stackName = " + stackName
    def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${userInput}"
    def liquibaseChange = "${liquibaseChangeParam}"

    def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'

    def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
    echo workspace

    if (liquibaseChange != 'null') {
      //do liquibase step
      stage("Liquibase Database Update") {
        echo "EXECUTE LIQUIDBASE"
        echo "${env.WORKSPACE}"
        liquibaseProcess(liquibaseProjectFolder, workspace, liquibaseChange,
                         secretRoot, appRoleName, false, 'null', 'non-prod')
      }
    }

    stage("Deploy to Non-Prod") {
      echo "EXECUTE DEV DEPLOY"
      deploymentString = deploy(workspace, imageTag, dockerhub, secretRoot, appRoleName,
                                organizationName, appGitRepoName, userInput, templates, secrets,
                                false, false, dockerfileToTagMap, gitBranchName, buildNumber,
                                gitCommit, crNumber)
    }

  } catch (err) {
    currentBuild.result = 'FAILED'
    throw err
  }
  return deploymentString
}

def getSecretProcessParameter(env, organizationName, appGitRepoName, isProd) {
  def part = (isProd)? "prod":"nonprod"
  def secretRootBase = "secret/${organizationName}/${appGitRepoName}/${part}"
  def secretRoot = (isProd)? "${secretRootBase}":"${secretRootBase}/${env}"
  def appRoleName = "${organizationName}-${appGitRepoName}-${part}"
  def appVaultAuthToken = generateVaultAuthToken(appRoleName, isProd);
  echo "application vault auth token -> ${appVaultAuthToken}"
  return [secretRoot, secretRootBase, appVaultAuthToken]
}

def secretProcess(templates, secrets, env, organizationName, appGitRepoName, isProd) {
  try {
    def (secretRoot, secretRootBase, appVaultAuthToken) = getSecretProcessParameter(env, organizationName, appGitRepoName, isProd)
    templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
  } catch (err) {
    currentBuild.result = 'FAILED'
    throw err
  }
}

def localBuildSteps(stageName, buildSteps) {
  if (buildSteps != 'null') {
    stage (stageName) {
      container('builder') {
        echo "EXECUTING BUILD & TESTING STEPS"
        buildSteps.split('\n').each { step ->
          sh step.trim()
        }
      }
    }
  }
}

def localBuildStepsForDockerServiceBuild(stageName, buildSteps) {
  if (buildSteps != null && buildSteps != 'null') {
    stage (stageName) {
      container('builder') {
        echo "EXECUTING BUILD & TESTING STEPS"
        setNpmrcFilelink();
        buildSteps.split('\n').each { step ->
          sh step.trim()
        }
      }
    }
  }
}

def createSecretFiles(organizationName, appGitRepoName, userInput, templates, secrets, imageTag, isDeployToDr, isProd) {
  try {

    def (secretRoot, secretRootBase, appVaultAuthToken) = getSecretProcessParameter(userInput, organizationName, appGitRepoName, isProd)

    container('vault') {
      withEnv(["VAULT_TOKEN=${appVaultAuthToken}",
              "SECRET_ROOT=${secretRoot}"]) {
        //construct the script server private key
        sh """
          consul-template -vault-renew-token=false -once -template /home/jenkins/.ssh/id_rsa_scriptserver.ctmpl:$workspace/id_rsa_scriptserver
          chmod 400 $workspace/id_rsa_scriptserver
        """
      }
    }

    templateProcessing(templates, secrets, secretRoot, secretRootBase, appVaultAuthToken)
    createKebernetesSecret(secrets, organizationName, appGitRepoName, userInput, isProd)
    updateKubeResourceForBatch(organizationName, appGitRepoName, userInput, secretRoot, appVaultAuthToken, imageTag, isProd, isDeployToDr)
  } catch (err) {
    currentBuild.result = 'FAILED'
    throw err
  }
}

def updateKubeResourceForBatch(organizationName, appGitRepoName, environment, secretRoot, appVaultAuthToken,
                               imageTag, isDeployToProd, isDeployToDr) {
  def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
  echo workspace
  def envMap = [:]
  def myEnv = environment
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
  envMap.TAG = "${imageTag}"
  envMap.ORG = "${organizationName}"
  envMap.REPO = "${repoR}"
  envMap.SPLUNK_TAG = "${env.SPLUNK_TAG}"
  //it should be set to prod for both prod & dr
  if (isDeployToProd) {
    envMap.ENV = 'prod'
  } else {
    envMap.ENV = "${myEnv}"
  }

  def scriptPath

  if (isDeployToProd) {
    envMap.REPO_KEY = "${env.IMAGE_REPO_PROD_KEY}"
    scriptPath="/opt/projects/shared/auth/jenkins/${organizationName}/${appGitRepoName}/prod"
  } else {
    envMap.REPO_KEY = "${env.IMAGE_REPO_NONPROD_KEY}"
    scriptPath="/opt/projects/shared/auth/jenkins/${organizationName}/${appGitRepoName}/${environment}"
  }

  println envMap
  def presetName = "${organizationName}-${appGitRepoName}-${environment}-podpreset"
  if (isDeployToProd) {
    presetName = "${organizationName}-${appGitRepoName}-prod-podpreset"
  }
  def namespacename = "${organizationName}-default"

  //deploy the yaml file in this folder
  processKubernetesYamlFile(isDeployToDr,  envMap, workspace, organizationName, appGitRepoName);

  if (!isDeployToDr) {
    deployBatchScript(workspace, envMap, appVaultAuthToken, secretRoot, scriptPath)
  }

}

def deployBatchScript(workspace, envMap, appVaultAuthToken, secretRoot, scriptPath) {
  def scriptServer = "${env.SCRIPT_SERVER}"
  echo scriptServer

  //container('vault') {
  //  withEnv(["VAULT_TOKEN=${appVaultAuthToken}",
  //          "SECRET_ROOT=${secretRoot}"]) {
  //    //construct the script server private key
  //    sh """
  //      consul-template -vault-renew-token=false -once -template /home/jenkins/.ssh/id_rsa_scriptserver.ctmpl:$workspace/id_rsa_scriptserver
  //      chmod 400 $workspace/id_rsa_scriptserver
  //    """
  //  }
  //}

  def foundFiles = sh(script: "ls -1 ${workspace}/kubernetes/*.sh", returnStdout: true).split()

  def dem = foundFiles.length
  def i = 0
  while (i < dem) {

    echo "i=" + i
    def findFileName = foundFiles[i]
    echo "find batch script fileFullPath -> " + findFileName
    def fileName = findArtifact(findFileName)
    echo "find batch script fileName -> " + fileName
    def fileContents = readFile("${workspace}/kubernetes/${fileName}")
    def result = fileContents
    envMap.each { k, v ->
        def keyStr = "${k}"
        def keyString = '\\$\\{' + keyStr + '\\}'
        result = result.replaceAll(keyString, v)
    }
    writeFile file: "${workspace}/${fileName}", text: result
    sh """
      cat $workspace/$fileName
    """

    echo "scriptPath -> ${scriptPath}"
    echo "copy to script server -> ${scriptServer}"

    sh """
      ssh -i $workspace/id_rsa_scriptserver jenkins@$scriptServer 'mkdir -p $scriptPath/logs'
      scp -i $workspace/id_rsa_scriptserver $workspace/$fileName jenkins@$scriptServer:$scriptPath/$fileName
    """

    i=i+1
  }

}

def secretsProcessingForBatchJobs(nonProdEnvs, organizationName, appGitRepoName, templates, secrets, imageTag, isDeployToDr, isProd) {
  echo nonProdEnvs
  nonProdEnvs.split("\n").each { env ->
    echo env
    createSecretFiles(organizationName, appGitRepoName, env, templates, secrets, imageTag, isDeployToDr, isProd)
  }
}

def getImagesTags(imageTags) {
  def gitTag = ""
  imageTags.split("\n").each { imageTag ->
    echo imageTag
    def cleanImageTag = imageTag.replace(":", "-")
    gitTag = (gitTag.length() == 0) ? cleanImageTag : "${gitTag}-${cleanImageTag}"
  }
  return gitTag
}

//Solaris deployment helper functions

def getStringFromMap(map) {
  def mymapString = ""
  map.each{ k, v ->
    if (mymapString == "") {
       mymapString = "${k}:${v}"
    } else {
       mymapString = mymapString +":::"+"${k}:${v}"
    }
  }
  return mymapString
}

def getMapFromString(str) {
  def map = [:]

  if (str?.trim()) {
    def md = str.split(":::")
    def dem = md.length
    def i = 0
    while(i<dem) {
      def p = md[i]
      print "\n" + p
      def mmd = p.split(":")
      def k = mmd[0]
      def v ='null'
      if (mmd.length > 1) {
        v = mmd[1]
      }
      map[k] = v
      i=i+1
    }
  }

  print map
  return map
}

def deployArtifact_secrets(remoteAppUser, host, src, dest, deployEnv, domainName="westernasset.com", os="linux") {
  def sa = src.split("\n")
  def da = dest.split("\n")
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def a = sa[i]
    def d = da[i]
    echo  a + " -> " + d
    //find the destination path
    def destinationPath = findPath(d)
    def mkdirCmd = "mkdir -p ${destinationPath}"

    def envMap = [:]
    //def xxx = "${deployEnv}.groovy"
    //sh """
    //  ls -la $workspace/conf/env/$xxx
    //"""
    if (fileExists("${workspace}/conf/env/${deployEnv}.groovy")) {
      echo "Yes, ${workspace}/conf/env/${deployEnv}.groovy exists"
      def tempScript = load "${workspace}/conf/env/${deployEnv}.groovy"
      envMap = tempScript.getEnvMap()
    } else {
      echo "No, ${workspace}/conf/env/${deployEnv}.groovy does not exist"
    }

    def fileContents = readFile("${workspace}/${a}")
    def result = fileContents

    def newSecretFileName = a
    if (envMap.size()>0) {
      //println 'envMap is not empty'
      //println envMap
      envMap.each{ key, value ->
        //println key + ',' + value
        def keyTokens = key.split('_')
        //println keyTokens
        def keyTokens_dem = keyTokens.length
        if (keyTokens_dem == 2) {
          def k = keyTokens[0]
          //println k
          def v = keyTokens[1]
          //println v
          //println host
          //println v == host
          //println v.equals(host)
          if (v.equals(host)) {
            //println 'where v == deployEnv'
            def keyString = '\\$\\{' + k + '\\}'
            result = result.replaceAll(keyString, value)
            newSecretFileName = "${a}_${deployEnv}"
          } else {
            //println 'no not the same xxx'
          }
        }
      }
    }

    writeFile file: "${workspace}/${newSecretFileName}", text: result
    //sh """
    //  cat $workspace/$newSecretFileName
    //"""

    try {
      echo "No, ${workspace}/${a} does not exist"
      a = findArtifact(a)
      if (fileExists("${workspace}/${a}")) {
        echo "Yes, ${workspace}/${a} exists"
      }
    } catch (e) {}

    if (!os.equalsIgnoreCase('windows')) {
      sh """
        ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host}.${domainName} $mkdirCmd
      """
    }

    def rmtDest = "${remoteAppUser}@${host}.${domainName}:$d"

    sh """
      scp -i ${workspace}/id_rsa $newSecretFileName $rmtDest
    """

    i=i+1
    echo "count =" + i
  }
}

def deployArtifact(remoteAppUser, host, src, dest, domainName, os) {
  def sa = src.split("\n")
  def da = dest.split("\n")
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def a = sa[i]
    def d = da[i]
    echo  a + " -> " + d
    //find the destination path
    def destinationPath = findPath(d)
    def mkdirCmd = "mkdir -p ${destinationPath}"

    if (a.endsWith("/.")) {
      sh """
        ssh -i ${workspace}/id_rsa $remoteAppUser@$host.$domainName '$mkdirCmd'
        scp -i ${workspace}/id_rsa -r $a $remoteAppUser@$host.$domainName:'$d'
      """
    } else {
      def aa = findArtifact(a)
      if (!fileExists("${workspace}/${a}")) {
        echo "No, ${workspace}/${a} does not exist"
        if (fileExists("${workspace}/${aa}")) {
          echo "Yes, ${workspace}/${aa} exists"
          a = aa
        }
      }

      if (!os.equalsIgnoreCase('windows')) {
        sh """
          ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host}.${domainName} $mkdirCmd
        """
      }
      sh """
        scp -i ${workspace}/id_rsa $a $remoteAppUser@$host.$domainName:'$d'
      """
    }

    i=i+1
    echo "count =" + i
  }
}

def runScripts(remoteAppUser, host, scripts, action, domainName) {
  echo "scripts -> ${scripts}"
  def sa = scripts.split('\n')
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def script = sa[i]
    echo "run script -> ${script} on ${host}.${domainName}"

    if (action == 'stop') {
      try {
        sh """
          ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host}.${domainName} '$script'
        """
      } catch(e) {
        echo e.getMessage()
      }
    } else {
      sh """
        ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host}.${domainName} '$script'
      """
    }

    i=i+1
    echo "count =" + i
  }
}

def parallelScriptRun(hostsString, remoteAppUser, scripts, action, domainName="westernasset.com") {
  def hosts = hostsString.split(',');
  try {
    dem = hosts.length
    def i = 0
    def steps = [:]
    while (i < dem) {
      echo "process i=server" + i
      def host = hosts[i]
      echo host
      echo "${action} server -> ${host}"
      steps[host] = {
        runScripts(remoteAppUser, host, scripts, action, domainName)
      }
      i=i+1
    }
    parallel steps
  } catch(e1) {
    echo e1.getMessage()
    throw e1
  }
}

def findPath(fileFullPath) {
  def str1 = fileFullPath.reverse()
  def sub1 = str1.substring(str1.indexOf("/")+1)
  def sub = sub1.reverse()
  return sub
}

def findArtifact(fileFullPath) {
  def str1 = fileFullPath.reverse()
  def sub1 = (str1.contains("/"))?str1.substring(0, str1.indexOf("/")):str1
  def sub = sub1.reverse()
  return sub
}

def findBrachAndVersion(tag) {
  def str1 = tag.reverse()
  def sub1 = str1.substring(str1.indexOf("-")+1)
  def sub = sub1.reverse()
  return sub
}

def deployPreInstallArtifacts(hostsString, remoteAppUser, preInstallArtifacts, preInstallArtifactsDests, domainName="westernasset.com", os="linux") {
  def hosts = hostsString.split(',');
  try {
    dem = hosts.length
    def i = 0
    def steps = [:]
    while (i < dem) {
      def host = hosts[i]
      echo host
      steps[host] = {
        echo "deploy scripts -> ${host}"
        //deploy the secrets
        if (preInstallArtifacts != "null") {
          deployArtifact(remoteAppUser, host, preInstallArtifacts, preInstallArtifactsDests, domainName, os)
        }
      }
      i=i+1
    }
    parallel steps
  } catch(pdError) {
    echo pdError.getMessage()
    throw pdError
  }
}

def deployArtifacts(hostsString, remoteAppUser, secrets, secretsRemoteDests, appArtifacts, appArtifactsRemoteDests, deployEnv, domainName="westernasset.com", os="linux") {
  def hosts = hostsString.split(',');
  try {
    dem = hosts.length
    def i = 0
    while (i < dem) {
      def host = hosts[i]
      echo host
      echo "deploy configurations -> ${host}"
      //deploy the secrets only the secrets exist and secret remote destination is defined
      if (secrets != "null" && secretsRemoteDests != 'null') {
        deployArtifact_secrets(remoteAppUser, host, secrets, secretsRemoteDests, deployEnv, domainName, os)
      }
      echo "deploy artifacts -> ${host}"
      //deploy the artifacts
      if (appArtifacts != "null") {
        deployArtifact(remoteAppUser, host, appArtifacts, appArtifactsRemoteDests, domainName, os)
      }
      i=i+1
    }
  } catch(dError) {
    echo dError.getMessage()
    throw dError
  }
}

def downloadArtifact(appArtifacts) {
  container('aws') {
    def artifacts = appArtifacts.split('\n');
    echo appArtifacts
    try {
      dem = artifacts.length
      echo "dem = ${dem}"
      def i = 0
      while (i < dem) {
        def artifact = artifacts[i]
        if (artifact.startsWith('http://artifactory')||artifact.startsWith('https://artifactory')) {
          println 'need to download'
          sh """
            cd $workspace
            curl -O -s --show-error $artifact
            ls -la
          """
        }
        i=i+1
      }
    } catch(dError) {
      echo dError.getMessage()
      throw dError
    }
  }
}

def getArchiveList(appArtifacts) {
  def mylist
  def artifacts = appArtifacts.split('\n');
  echo appArtifacts
  try {
    dem = artifacts.length
    echo "dem = ${dem}"
    def i = 0
    while (i < dem) {
      def artifact = artifacts[i]
      if (!artifact.startsWith('http://artifactory') && !artifact.startsWith('https://artifactory')) {
         if (mylist == null) {
           mylist = artifact
         } else {
           mylist = mylist + '\n' + artifact
         }
      }
      i=i+1
    }
  } catch(dError) {
    echo dError.getMessage()
    throw dError
  }
  return mylist
}

def archive(appArtifacts) {
  def artifacts = appArtifacts.split('\n');
  echo appArtifacts
  try {
    dem = artifacts.length
    echo "dem = ${dem}"
    def i = 0
    while (i < dem) {
      def artifact = artifacts[i]
      if (!(artifact.startsWith('http://artifactory')||artifact.startsWith('https://artifactory'))) {
        echo "archive -> ${artifact}"
        sh """
          ls -la $artifact
        """
        archiveArtifacts "${artifact}"
      }
      i=i+1
    }
  } catch(dError) {
    echo dError.getMessage()
    throw dError
  }
}

def copyArtifactsFromUpstream(upstreamJobName, upstreamBuildNumber, appArtifacts) {
  def artifacts = appArtifacts.split('\n');
  try {
    dem = artifacts.length
    def i = 0
    def artifactParentPath = [:]
    while (i < dem) {
      def artifact = artifacts[i]
      echo "archive -> ${artifact}"

      copyRemoteArtifacts(from: "jenkins://b01399c8b0598a55636663fe598c6d09/${upstreamJobName}",
        includes: "${artifact}",
        selector: [$class: 'SpecificRemoteBuildSelector', number: "${upstreamBuildNumber}"],
        timeout: '1h 2m 20s');

      sh """
        ls -la
      """

      i=i+1
    }
  } catch(dError) {
    echo dError.getMessage()
    throw dError
  }
}

def pushArtifactsToDatabrickS3Bucket(credentialsId, appArtifactsString, bucket, key, projectName, buildNumber, isProd) {
  container('aws') {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}",
      usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {

      if (projectName != 'null') {
        copyArtifacts(projectName: "${projectName}", selector: specific("${buildNumber}"))
      }

      def artifacts = appArtifactsString.split('\n');
      echo appArtifactsString
      try {
        dem = artifacts.length
        echo "dem = ${dem}"
        def i = 0
        while (i < dem) {
          def artifact = artifacts[i]
          println artifact

          def item
          def itemFilename

          if (artifact.startsWith("http://artifactory")||artifact.startsWith("https://artifactory")) {
            println artifact

            item = findArtifact(artifact)
            itemFilename = findArtifact(artifact)

            println itemFilename
            println item
            sh """
               aws s3 cp $item s3://$bucket/$key/$itemFilename --no-verify-ssl
            """
            sh """
              aws s3 ls s3://$bucket/$key/$itemFilename --no-verify-ssl
            """
          } else {
            def artifactReturn = sh(returnStdout: true, script: "ls -la ${artifact}")
            println artifactReturn

            def artifactsTokens = artifactReturn.split('\n')
            println artifactsTokens

            def dem = artifactsTokens.length
            def ii = 0
            while (ii < dem) {
              echo "ii=" + ii
              def findFileName = artifactsTokens[ii]
              println findFileName

              item = findFileName.tokenize(' ')[-1]

              itemFilename = findArtifact(item)

              println itemFilename
              println item

              if (isProd) {
                if (!itemFilename.contains("SNAPSHOT") && !itemFilename.contains('snapshot')) {
                  sh """
                    aws s3 cp $item s3://$bucket/$key/$itemFilename --no-verify-ssl
                  """
                  sh """
                    aws s3 ls s3://$bucket/$key/$itemFilename --no-verify-ssl
                  """
                }
              } else {
                if (itemFilename.contains("SNAPSHOT") || itemFilename.contains('snapshot')) {
                  sh """
                     aws s3 cp $item s3://$bucket/$key/$itemFilename --no-verify-ssl
                  """
                  sh """
                    aws s3 ls s3://$bucket/$key/$itemFilename --no-verify-ssl
                  """
                }
              }
              ii=ii+1
            }
          }
          i=i+1
        }
      } catch(dError) {
        echo dError.getMessage()
        throw dError
      }
    }
  }
}

def sbtSnapshotBuild() {
  container('sbt') {
    //sh 'ls -la /home/jenkins'
    //sh 'ls -la /home/jenkins/.ssh'
    //sh 'ls -la /home/jenkins/vault'
    //sh 'cat /home/jenkins/.ssh/config'
    //sh 'set'
    //echo sh(script: 'env|sort', returnStdout: true)
    echo "EXECUTE SBT SNAPSHOT BUILD"
    sh """
      git config -l
      sbt -Dsbt.log.noformat=true clean compile
      sbt -Dsbt.log.noformat=true package
      ls -la
      ls -la ./target
      ls -la ./target/scala-2.12
      sbt -Dsbt.log.noformat=true publishLocal
      ls -la
      ls -la ./target
      ls -la ./target/scala-2.12
      sbt -Dsbt.log.noformat=true publish
      ls -la
      ls -la ./target
      ls -la ./target/scala-2.12
    """
  }
}

def postDeployStepsLogic(postDeploySteps, deploymentName) {
  print postDeploySteps
  print deploymentName
  if (postDeploySteps != 'null') {
    stage ('Post Deploy Verification') {
      def bool = false
      def errorExpection
      container('builder') {
        def steps = postDeploySteps.split('\n');
        try {
          dem = steps.length
          echo "number of post deploy varification steps = ${dem}"
          def i = 0
          while (i < dem) {
            def step = steps[i]
            println step

            sh """
              $step
            """

            i=i+1
          }
        } catch(dError) {
          echo dError.getMessage()
          bool = true
          errorExpection = dError
        }
      }
      if (bool) {
        container('kubectl') {
          sh """
            kubectl rollout history $deploymentName
            kubectl rollout undo $deploymentName
            kubectl rollout history $deploymentName
          """
        }
        throw errorExpection
      }
    }
  }
}

def backupResourceForRollback(hostsString, remoteAppUser, appArtifactsRemoteDests, backupDest){
  def hosts = hostsString.split(',');
  try {
    dem = hosts.length
    def i = 0
    def steps = [:]
    while (i < dem) {
      echo "process i=server" + i
      def host = hosts[i]
      echo host
      echo "server -> ${host}"
      steps[host] = {
         backupResources(remoteAppUser, host, appArtifactsRemoteDests, backupDest)
      }
      i=i+1
    }
    parallel steps
  } catch(e1) {
    echo e1.getMessage()
    throw e1
  }
}

def backupResources(remoteAppUser, host, appArtifactsRemoteDests, backupDest) {
  def sa = appArtifactsRemoteDests.split('\n')
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def resource = sa[i]
    def script = ''
    echo "run script -> ${script} on ${host}"

    def destinationPath = findPath(resource)
    def tarFileName_path = destinationPath.replaceAll("/", "_") + ".tar"
    def tarFileName_file = resource.replaceAll("/", "_") + ".tar"

    def makeDir = "mkdir -p ${backupDest}"

    def tarDir = "tar -cvf  ${backupDest}/${tarFileName_path} ${destinationPath}"
    def tarFile = "tar -cvf ${backupDest}/${tarFileName_file} ${resource}"

    //tar xf tar.file -C /

    if (resource.endsWith("/.")) {
      try {
        sh """
          ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host} $makeDir
          ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host} $tarDir
        """
      } catch(err) {
        print err.getMessage()
      }
    } else {
      try {
        sh """
          ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host} $makeDir
          ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host} $tarFile
        """
      } catch(err) {
        print err.getMessage()
      }
    }

    i=i+1
    echo "count =" + i
  }
}

def restoreResourceForRollback(hostsString, remoteAppUser, appArtifactsRemoteDests, backupDest){
  def hosts = hostsString.split(',');
  try {
    dem = hosts.length
    def i = 0
    def steps = [:]
    while (i < dem) {
      echo "process i=server" + i
      def host = hosts[i]
      echo host
      echo "server -> ${host}"
      steps[host] = {
         restoreResources(remoteAppUser, host, appArtifactsRemoteDests, backupDest)
      }
      i=i+1
    }
    parallel steps
  } catch(e1) {
    echo e1.getMessage()
    throw e1
  }
}

def restoreResources(remoteAppUser, host, appArtifactsRemoteDests, backupDest) {
  def sa = appArtifactsRemoteDests.split('\n')
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def resource = sa[i]
    def script = ''
    echo "run script -> ${script} on ${host}"

    def destinationPath = findPath(resource)
    def tarFileName_path = destinationPath.replaceAll("/", "_") + ".tar"
    def tarFileName_file = resource.replaceAll("/", "_") + ".tar"

    def untarDir = "tar -xvf ${backupDest}/${tarFileName_path} -C /"
    def untarFile = "tar -xvf ${backupDest}/${tarFileName_file} -C /"

    if (resource.endsWith("/.")) {
      try {
        sh """
          ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host}.westernasset.com $untarDir
        """
      } catch(err) {
        print err.getMessage()
      }
    } else {
      try {
        sh """
          ssh -i ${workspace}/id_rsa ${remoteAppUser}@${host}.westernasset.com $untarFile
        """
      } catch(err) {
        print err.getMessage()
      }
    }

    i=i+1
    echo "count =" + i
  }
}

def awsCDKSynth(accounts, accountAppfileMap, appfileStackMap) {
  println 'accountsm-->' + accounts
  println 'accountAppfileMap-->' + accountAppfileMap
  println 'appfileStackMap-->' + appfileStackMap

  def accountToAppfileMap = getMapFromString(accountAppfileMap)
  def appfileToStackMap = getMapFromString(appfileStackMap)

  def sa = accounts.split('\n')
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def profile = sa[i]
    println 'profile->>' + profile
    def appFiles = accountToAppfileMap[profile]
    if (appFiles != null) {
      println 'appFiles->>' + appFiles
      def appfileArray = appFiles.split(',');
      def afDem = appfileArray.length
      def k = 0
      while (k < afDem) {
        echo 'k=' + k
        def appFile = appfileArray[k].trim()
        echo 'appFile->>>' + appFile
        def stackNames = appfileToStackMap[appFile]
        echo 'stackNames->>>' + stackNames
        if (stackNames != null) {
          def st = stackNames.split(',')
          def stDem = st.length
          def j = 0
          while (j < stDem) {
            echo "j=" + j
            def stack = st[j].trim()
            println 'stack :: ' + stack
            def af = "${appFile}.js"
            sh """
              cdk synth $stack --app $af --profile $profile
            """
            j=j+1
          }
        }
        k=k+1
      }
    }
    i=i+1
  }
}

def awsCDKDiff(accounts, accountAppfileMap, appfileStackMap) {
  def accountToAppfileMap = getMapFromString(accountAppfileMap)
  def appfileToStackMap = getMapFromString(appfileStackMap)

  def sa = accounts.split('\n')
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def profile = sa[i]
    def appFiles = accountToAppfileMap[profile]
    if (appFiles != null) {
      println 'appFiles->>' + appFiles
      def appfileArray = appFiles.split(',');
      def afDem = appfileArray.length
      def k = 0
      while (k < afDem) {
        echo 'k=' + k
        def appFile = appfileArray[k].trim()
        def stackNames = appfileToStackMap[appFile]
        if (stackNames != null) {
          println 'stackNames -->' + stackNames
          def st = stackNames.split(',')
          def stDem = st.length
          def j = 0
          while (j < stDem) {
            echo "j=" + j
            def stack = st[j].trim()
            println 'stack :: ' + stack
            def af = "${appFile}.js"
            try {
              sh """
                cdk diff $stack --app $af --profile $profile
              """
            } catch(e) { println e }
            j=j+1
          }
        }
        k=k+1
      }
    }
    i=i+1
  }
}

def awsCDKDeploy(accounts, organizationName, appGitRepoName, accountAppfileMap, appfileStackMap) {
  def accountToAppfileMap = getMapFromString(accountAppfileMap)
  def appfileToStackMap = getMapFromString(appfileStackMap)

  def sa = accounts.split('\n')
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def profile = sa[i]
    def appFiles = accountToAppfileMap[profile]
    if (appFiles != null) {
      println 'appFiles->>' + appFiles
      def appfileArray = appFiles.split(',');
      def afDem = appfileArray.length
      def k = 0
      while (k < afDem) {
        echo 'k=' + k
        def appFile = appfileArray[k].trim()
        println 'appFile ->' + appFile
        def stackNames = appfileToStackMap[appFile]
        if (stackNames != null) {
          println 'stackNames -->' + stackNames
          def st = stackNames.split(',')
          def stDem = st.length
          def j = 0
          while (j < stDem) {
            echo "j=" + j
            def stack = st[j].trim()
            println 'stack :: ' + stack
            def af = "${appFile}.js"
            println 'af ->' + af
            sh """
              cdk deploy $stack --app $af --require-approval never --tags 'wam:git-organization'=$organizationName --tags 'wam:git-repository'=$appGitRepoName --profile $profile
            """
            j=j+1
          }
        }
        k=k+1
      }
    }
    i=i+1
  }
}

def awsCDKDestroy(accounts, accountAppfileMap, appfileStackMap) {
  def accountToAppfileMap = getMapFromString(accountAppfileMap)
  def appfileToStackMap = getMapFromString(appfileStackMap)

  def sa = accounts.split('\n')
  def dem = sa.length
  def i = 0
  while (i < dem) {
    echo "i=" + i
    def profile = sa[i]
    def appFiles = accountToAppfileMap[profile]
    if (appFiles != null) {
      println 'appFiles->>' + appFiles
      def appfileArray = appFiles.split(',');
      def afDem = appfileArray.length
      def k = 0
      while (k < afDem) {
        echo 'k=' + k
        def appFile = appfileArray[k].trim()
        def stackNames = appfileToStackMap[appFile]
        if (stackNames != null) {
          println 'stackNames :: ' + stackNames
          def st = stackNames.split(',')
          //when destroy reverse the stack order (first in last out)
          st.reverse();
          def stDem = st.length
          def j = 0
          while (j < stDem) {
            echo "j=" + j
            def stack = st[j].trim()
            println 'stack :: ' + stack
            def af = "${appFile}.js"
            sh """
              cdk destroy --app $af --profile $profile  --force $stack
            """
            j=j+1
          }
        }
        k=k+1
      }
    }
    i=i+1
  }
}

def setNpmrcFilelink() {
  sh """
    cp /home/jenkins/.npm/.npmrc /home/jenkins/.npmrc
    chmod +r /home/jenkins/.npmrc
    ls -la /home/jenkins
  """
}

def npmBuild() {
  sh """
    npm install
    npm run build
  """
}

def awsCFDryrun(accounts, accountAppfileMap, appfileStackMap, parametersOverridesMap, organizationName, appGitRepoName) {
  def accountToAppfileMap = getMapFromString(accountAppfileMap)
  def appfileToStackMap = getMapFromString(appfileStackMap)
  def parametersMap = getMapFromString(parametersOverridesMap)

  def sa = accounts.split('\n')
  def dem = sa.length
  def i = 0

  while (i < dem) {
    echo "i=" + i
    def profile = sa[i]

    def s3 = 'wa-devops-n'
    if (profile == 'prod') {
      s3 = 'wa-devops-x'
    } else if (profile == 'sandbox') {
      s3 = 'wa-devops-s'
    }

    echo "profile = ${profile} s3=${s3}"

    def appFiles = accountToAppfileMap[profile]
    println accountToAppfileMap

    if (appFiles != null) {
      println 'appFiles->>' + appFiles
      def appfileArray = appFiles.split(',');
      def afDem = appfileArray.length
      def k = 0
      while (k < afDem) {
        echo 'k=' + k
        def appFile = appfileArray[k].trim()
        println appfileArray
        echo "appFile = ${appFile}"
        def stackName = appfileToStackMap[appFile]
        println appfileToStackMap
        echo "stackName = ${stackName}"
        if (stackName != null) {
          println 'stackName -->' + stackName

          def parameterKey = profile + '-' + stackName
          def parameters = parametersMap[parameterKey]

          def dryrunCmd = "aws cloudformation deploy --template-file ${workspace}/${appFile} --stack-name ${stackName} --s3-bucket ${s3} --s3-prefix cf --no-execute-changeset --profile ${profile}"
          dryrunCmd = dryrunCmd + " --tags organization=${organizationName} application=${appGitRepoName} environment=${stackName} "

          if (parameters != null) {
            println 'extra parameters = ' + parameters
            dryrunCmd = dryrunCmd + " --parameter-overrides ${parameters}"
          }

          try {
            def dryrunCmdStdout = sh(returnStdout: true, script: "${dryrunCmd}").trim()
            println dryrunCmdStdout
            def dryrunToken = dryrunCmdStdout.split('\n')
            def lastOne
            dryrunToken.each {
              lastOne = "${it}"
            }
            sh """
              $lastOne --profile $profile
            """
          } catch(e) {
            def msg = e.getMessage()
            println 'msg->'+msg
            if (!msg.contains('code 255')) {
              throw e
            }
          }

        }
        k=k+1
      }
    }
    i=i+1
  }
}

def awsCFDeploy(accountAppfileMap, appfileStackMap, parametersOverridesMap, organizationName, appGitRepoName, deployEnv) {
  def accountToAppfileMap = getMapFromString(accountAppfileMap)
  println 'accountToAppfileMap=' + accountToAppfileMap

  def appfileToStackMap = getMapFromString(appfileStackMap)
  println 'appfileToStackMap=' + appfileToStackMap

  def parametersMap = getMapFromString(parametersOverridesMap)
  println 'parametersMap=' + parametersMap

  def s3 = 'wa-devops-n'
  if (deployEnv == 'prod') {
    s3 = 'wa-devops-x'
  } else if (deployEnv == 'sandbox') {
    s3 = 'wa-devops-s'
  }

  echo "deployEnv = ${deployEnv} s3=${s3}"

  def appFiles = accountToAppfileMap[deployEnv]
  println accountToAppfileMap

  if (appFiles != null) {
    println 'appFiles->>' + appFiles
    def appfileArray = appFiles.split(',');
    def afDem = appfileArray.length
    def k = 0
    while (k < afDem) {
      echo 'k=' + k
      def appFile = appfileArray[k].trim()
      println appfileArray
      echo "appFile = ${appFile}"
      def stackName = appfileToStackMap[appFile]
      println appfileToStackMap
      echo "stackName = ${stackName}"
      if (stackName != null) {
        println 'stackName -->' + stackName

        def parameterKey = deployEnv + '-' + stackName
        def parameters = parametersMap[parameterKey]

        def deployCmd = "aws cloudformation deploy --template-file ${workspace}/${appFile} --stack-name ${stackName} --s3-bucket ${s3} --s3-prefix cf --profile ${deployEnv}"
        deployCmd = deployCmd + " --tags 'wam:git-organization'=${organizationName} 'wam:git-repository'=${appGitRepoName} 'wam:environment'=${stackName} --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND"

        if (parameters != null) {
          println 'extra parameters = ' + parameters
          deployCmd = deployCmd + " --parameter-overrides ${parameters}"
        }

        try {
          sh """
            $deployCmd
          """
        } catch(e) {
          def msg = e.getMessage()
          println 'msg->'+msg
          if (!msg.contains('code 255')) {
            throw e
          }
        }

      }
      k=k+1
    }
  }
}

def awsCFDestroy(deployEnv, accountAppfileMap, appfileStackMap) {

  def accountToAppfileMap = getMapFromString(accountAppfileMap)
  println 'accountToAppfileMap=' + accountToAppfileMap

  def appfileToStackMap = getMapFromString(appfileStackMap)
  println 'appfileToStackMap=' + appfileToStackMap

  def appFiles = accountToAppfileMap[deployEnv]
  println accountToAppfileMap

  if (appFiles != null) {
    println 'appFiles->>' + appFiles
    def appfileArray = appFiles.split(',');
    def afDem = appfileArray.length
    def k = 0
    while (k < afDem) {
      echo 'k=' + k
      def appFile = appfileArray[k].trim()
      println appfileArray
      echo "appFile = ${appFile}"
      def stackName = appfileToStackMap[appFile]
      println appfileToStackMap
      echo "stackName = ${stackName}"
      if (stackName != null) {
        println 'stackName -->' + stackName
        sh """
          aws cloudformation delete-stack --stack-name $stackName --profile $deployEnv
        """
      }
      k=k+1
    }
  }
}

def processTableauResource(files, names, secrets, projects, resourceType, deployEnv, tabbedFlag, organizationName, appGitRepoName)  {
  def tableauUrl = "${env.TABLEAU_URL}"
  def site
  def isDeployToProd = true

  if (deployEnv == 'dev' || deployEnv == 'Dev' || deployEnv == 'DEV') {
    tableauUrl = "${env.TABLEAU_URL}/#"
    isDeployToProd = false
  } else if (deployEnv == 'qa' || deployEnv == 'Qa' || deployEnv == 'QA') {
    tableauUrl = "${env.TABLEAU_URL}/#/site/QA"
    site = 'QA'
    isDeployToProd = false
  } else {
    tableauUrl = "${env.TABLEAU_URL}/#"
    isDeployToProd = true
  }

  if (files != 'null') {
    def f = files.split('\n')
    def n = names.split('\n')
    def p = projects.split('\n')
    def s = secrets.split('\n')

    def fdam = f.length
    def i = 0
    while (i < fdam) {
      def file = f[i]
      echo "index=" + i
      def name = n[i]
      def project = p[i]

      def secret = null
      def user = null
      def pass = null

      if (secrets != 'null') {
        if (s[i]!='none' && s[i]!='None' && s[i]!= 'NONE') {
          secret = s[i]
          def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${secret}"
          def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'
          if (isDeployToProd) {
            secretRoot = "secret/${organizationName}/${appGitRepoName}/prod/${secret}"
            appRoleName = organizationName + '-' + appGitRepoName + '-prod'
          }
          //get the app vault auth token
          def appVaultAuthToken = generateVaultAuthToken(appRoleName, isDeployToProd);
          container('tableau') {
            sh """
              cp /opt/db.ctmpl $workspace/db.ctmpl
            """
          }
          container('vault') {
            withEnv(["VAULT_TOKEN=${appVaultAuthToken}", "SECRET_ROOT=${secretRoot}"]) {
              sh """
                consul-template -vault-renew-token=false -once -template $workspace/db.ctmpl:$workspace/db.groovy
              """
              def tempScript = load "$workspace/db.groovy"
              def envMap = tempScript.getSecretMap()
              user = envMap['username']
              pass = envMap['password']
            }
          }
        }
      }

      container('tableau') {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.TABLEAU_CREDENTIAL}",
          usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
          def siteArg = (site != null) ? "-t ${site}" : ""
          def secretArg = (secret != null) ? "--db-username '$user' --db-password '$pass' --save-db-password" : ""
          def secretArgDisplay = (secret != null) ? "--db-username '$user' --db-password 'xxxxxx' --save-db-password" : ""
          def tabbedArg = (resourceType == 'twb' && tabbedFlag) ? "--tabbed" : ""
          def displayCmd ="tabcmd publish ${file} --name ${name} --overwrite --project ${project} $secretArgDisplay --no-certcheck --accepteula $tabbedArg "

          sh """
            tabcmd login -s $tableauUrl -u $USERNAME -p $PASSWORD $siteArg --accepteula --no-certcheck
            set +x
            tabcmd publish '$file' --name '$name' --overwrite --project '$project' $secretArg --no-certcheck --accepteula $tabbedArg
            set -x
          """
        }
      }
      i = i + 1
    }
  } else {
    if (names != 'null') {
      def nlist = names.split('\n')
      def plist = projects.split('\n')

      def ndam = nlist.length
      def i = 0
      while (i < ndam) {
        echo "index=" + i
        def name = nlist[i]
        def project = plist[i]

        container('tableau') {
          withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.TABLEAU_CREDENTIAL}",
            usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
            def siteArg = (site != null) ? "-t ${site}" : ""
            def displayCmd ="tabcmd delete '${name}' --project ${project} --no-certcheck --accepteula".toString()
            sh """
              tabcmd login -s $tableauUrl -u $USERNAME -p $PASSWORD $siteArg --accepteula --no-certcheck
              echo $displayCmd
              set +x
              tabcmd delete '${name}' --project '${project}' --no-certcheck --accepteula
              set -x
            """
          }
        }

        i=i+1
      }
    }
  }
}

def snowflackScriptTokenReplacement(deployEnv) {
  def envMap = [:]
  if (fileExists("${workspace}/conf/env/${deployEnv}.groovy")) {
    echo "Yes, ${workspace}/conf/env/${deployEnv}.groovy exists"
    def envFile = load "${workspace}/conf/env/${deployEnv}.groovy"
    envMap = envFile.getEnvMap()
    snowflackScriptTokenReplacementFromFolder('deploy', envMap)
    snowflackScriptTokenReplacementFromFolder('verify', envMap)
    snowflackScriptTokenReplacementFromFolder('revert', envMap)
  }
}

def snowflackScriptTokenReplacementFromFolder(foldName, envMap) {
  //looking for the template file
  def foundFiles = sh(script: "ls -1 ${workspace}/${foldName}/*.ctmpl 2> /dev/null || true", returnStdout: true).trim().split()
  foundFiles = foundFiles + sh(script: "ls -1 ${workspace}/${foldName}/*.tmpl.sql 2> /dev/null || true", returnStdout: true).trim().split()

  //proces template file
  foundFiles.each { foundFileName ->
    echo "found template fileFullPath -> " + foundFileName
    def foundArtifactName = findArtifact(foundFileName)
    echo "found artifact name -> " + foundArtifactName
    def path = findPath(foundFileName)
    echo "path = " + path
    def finalFileName = foundArtifactName
      .replaceAll(/\.tmpl\.sql$/, ".sql")
      .replaceAll(/\.ctmpl$/, ".sql")
    echo "final file name -> " + finalFileName

    def result = readFile(foundFileName)
    envMap.each { k, v ->
      result = result.replaceAll('\\$' + k, v)
    }

    writeFile file: "${path}/${finalFileName}", text: result

    sh """
      ls -la $path
      cat $path/$finalFileName
    """
  }
}

def checkSqichTarget(deployEnv) {
  def hasTarget = false
  container('sqitch') {
    def checkTarget = sh(script: "sqitch target show ${deployEnv}", returnStatus: true)
    hasTarget = checkTarget == 0
  }
  return hasTarget
}

def snowflakeDeployStatus(organizationName, appGitRepoName, deployEnv, isDeployToProd, verifyFromChange, hasTarget) {
  def changeFrom = verifyFromChange
  print changeFrom

  def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${deployEnv}"
  def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'
  if (isDeployToProd) {
    secretRoot = "secret/${organizationName}/${appGitRepoName}/prod"
    appRoleName = organizationName + '-' + appGitRepoName + '-prod'
  }

  def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
  echo workspace

  //get the app vault auth token
  def appVaultAuthToken = generateVaultAuthToken(appRoleName, isDeployToProd);

  container('sqitch') {
    sh """
      cp /opt/snowsql.ctmpl $workspace/snowsql.ctmpl
    """
  }

  container('vault') {
    withEnv(["VAULT_TOKEN=${appVaultAuthToken}", "SECRET_ROOT=${secretRoot}"]) {
      sh """
        dir
        ls -la /opt
        ls -la /opt/resources
        consul-template -vault-renew-token=false -once -template $workspace/snowsql.ctmpl:$workspace/snowsql.properties
      """
    }
  }

  container('sqitch') {
    load "${workspace}/snowsql.properties"
    try {
      if (hasTarget) {
        def statusString = sh(script: "sqitch status ${deployEnv}", returnStdout: true)
        print statusString

        //
        // Determine the argument for --from-change option of `sqitch verify` command
        // based upon the output string from `sqitch status` command.
        //
        // There may be multiple changes from the status output.  To verify, we pick
        // the first one to verify from.
        //
        // There are two possible formats as shown below
        //   * rpt_obligor_stats_vw @v1.0.3
        //   * rpt_seg_stats_vw
        //
        // If there's a version number, return something like "rpt_obligor_stats_vw@v1.0.3"
        // If there's no version number, return something like "rpt_seg_stats_vw@HEAD"
        //

        def targetString = null
        if (statusString.contains("Undeployed change")){
          def lines = statusString.split("\n").findAll {
            it.trim()[0] == '*'  // exclude non-changes
          }
          def firstChange = lines[0]
          def words = firstChange.trim().split(" ")
          targetString = words.size() > 2 ? words[1] + words[2] : words[1] + "@HEAD"
        }
        changeFrom = targetString != null ? targetString: changeFrom
      }
    } catch (e) {
      print e
    }
  }

  return changeFrom
}

def snowflakeDeploy(deployEnv, isDeployToProd, sqitchMode, verifyFromChange, hasTarget) {
  def verifyFromChangeOpt = (verifyFromChange != null && verifyFromChange != 'null') ? "--from-change ${verifyFromChange}" : ' '
  print verifyFromChange

  container('sqitch') {
    load "${workspace}/snowsql.properties"
    def target = hasTarget ?
      deployEnv :
      "db:snowflake://${SNOWSQL_USER}@${SNOWSQL_ACCOUNT}/${SNOWSQL_DB}?Driver=SnowflakeDSIIDriver;warehouse=${SNOWSQL_WAREHOUSE}"

    verifyFromChangeOpt = sqitchMode != 'verify'?'':verifyFromChangeOpt

    if (!(sqitchMode == 'verify' && !verifyFromChangeOpt?.trim())) {
      sh """
        export PATH=/opt/snowsql/bin:$PATH
        snowsql -v
        sqitch $sqitchMode $verifyFromChangeOpt '$target'
      """
    } else {
      println "Nothing to verify"
    }
  }

}

def snowflakeDeploy(deployEnv, isDeployToProd, sqitchMode, hasTarget) {
  snowflakeDeploy(deployEnv, isDeployToProd, sqitchMode, 'null', hasTarget)
}

def hadolintDockerFile(dockerFile, failOnLint = false)  {
  stage('Lint Dockerfile') {
    container('docker') {
      try {
        sh(label: 'Lint Dockerfile', script: "docker run --rm -i $env.TOOL_HADOLINT < $workspace/$dockerFile")
      } catch (e) {
        if (failOnLint) {
          error(e)
        } else {
          unstable('Dockerfile linter errors found')
        }
      }
    }
  }
}

def getSamParameterList(profile) {
  def envMap = [:]
  def envFile = "${workspace}/conf/env/${profile}.groovy"
  if (fileExists(envFile)) {
    echo "Yes, ${envFile} exists"
    def tempScript = load envFile
    echo "${envFile} loaded"
    envMap = tempScript.getEnvMap()
    print envMap
  }
  def parametersOpts = ""
  for (m in envMap) {
    if (!parametersOpts.isEmpty()) {
      parametersOpts = parametersOpts + " "
    }
    parametersOpts = "${parametersOpts}${m.key}=${m.value}"
  }
  if (!parametersOpts.isEmpty()) {
    parametersOpts = "--parameter-overrides ${parametersOpts}"
  }
  print "parametersOpts = " + parametersOpts
  return parametersOpts
}

def getEnironmentMap(env) {
  def envMap = [:]
  def envFile = "${workspace}/conf/env/${env}_scripts.groovy"
  if (fileExists(envFile)) {
    echo "Yes, ${envFile} exists"
    def tempScript = load envFile
    envMap = tempScript.getEnvMap()
    print envMap
  }
  return envMap
}

def getScriptsByName(envMap, attributeName) {
  def attributes = envMap[attributeName]
  return (attributes == null)? 'null':attributes.join("\n")
}

def findRemoteAppUser(remoteAppUser, deployEnv) {
  if (remoteAppUser != 'null') {
    return remoteAppUser
  } else {
    def envMap = [:]
    def envFile = "${workspace}/conf/env/${deployEnv}.groovy"
    if (fileExists(envFile)) {
      echo "Yes, ${envFile} exists"
      def envF = load envFile
      envMap = envF.getEnvMap()
    }
    return envMap.remoteAppUser != null? envMap.remoteAppUser : 'null'
  }
}

def getDomainName(hostDomain, envMap) {
  return (envMap.hostDomain!=null)?envMap.hostDomain:((!hostDomain.equalsIgnoreCase('null'))? hostDomain : 'westernasset.com')
}

def processLiquibaseTemplates(templates, deployEnv) {
  if (templates?.trim()) {
    def map = getMapFromString(templates)
    println map
    def envMap = [:]
    def envFile = "${workspace}/conf/env/${deployEnv}.groovy"
    if (fileExists(envFile)) {
      echo "Yes, ${envFile} exists"
      def envF = load envFile
      envMap = envF.getEnvMap()
    } else {
      error("${workspace}/conf/env/${deployEnv}.groovy is not found!!!")
    }
    println envMap
    map.each { key, val ->
      print "key = ${key}, value = ${val}"
      def fileContents = readFile("${workspace}/${key}")
      def result = applyEnvMap(fileContents, envMap)
      println "for template -> ${key}, the content in file -> ${val}\n" + result
      writeFile file: "${workspace}/${val}", text: result
    }
  }
}

def getNonProdCluster(nonProdEnvs, deplyEnv) {
  def tokens = nonProdEnvs.split("\n")
  def deployEnv
  def clusterName
  tokens.each { e ->
    if (e == deplyEnv) {
      def t = e.split(':')
      deployEnv = t[0]
      clusterName = (t.size()>1)? t[1]: "pas-development"
    }
  }
  return [deployEnv, clusterName]
}

def nonprodDeployment(deployEnv, clusterName, repo, gitBranchName, templates, secrets, imageTag, isDeployToDr, isProd) {
  def commons = new com.westernasset.pipeline.Commons()
  podTemplate(
    cloud: clusterName,
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'kubectl', image: env.TOOL_KUBECTL, ttyEnabled: true),
      containerTemplate(name: 'vault', image: env.TOOL_VAULT, ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
  ]) {

    node(POD_LABEL) {
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${repo.gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${repo.gitCommit}"
        stage("Deploy to Non-Prod") {
          commons.secretsProcessingForBatchJobs(deployEnv, repo.organizationName, repo.appGitRepoName, templates, secrets, imageTag, isDeployToDr, isProd)
        }
      } catch(Exception e) {
        currentBuild.result = 'FAILED'
        throw e
      }
    }

  }
}
