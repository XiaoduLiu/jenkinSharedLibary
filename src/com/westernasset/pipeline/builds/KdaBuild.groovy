
package com.westernasset.pipeline.builds

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*

def prodDeploy(config, projectName, buildNumber, environment, repo, upstreamJobName, upstreamBuildNumber, archiveMap) {
  deploy(config, projectName, buildNumber, environment, null, repo, upstreamJobName, upstreamBuildNumber, archiveMap)
}

def nonprodDeploy(config, projectName, buildNumber, environment, branchName, archiveMap) {
  deploy(config, projectName, buildNumber, environment, branchName, null, null, null, archiveMap)
}

def deploy(config, projectName, buildNumber, environment, branchName, repo, upstreamJobName, upstreamBuildNumber, archiveMap) {

    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def ssh = new Ssh()
    def git = new Git()
    def aws = new Aws()
    def tg = new Terragrunt()
    def vault = new Vault()
    def slack = new Slack()

    def envToken = environment.split(':')
    String deployEnv = envToken[0]
    String account = envToken[1]
    String eksCluster = aws.getEksCluster(account)

    def sshVolume = (deployEnv == 'prod')? ssh.prodKeysVolume():ssh.keysVolume()
    def awsVolume = (deployEnv == 'prod')? aws.prodAwsVolume():aws.awsVolume()

    properties([
      copyArtifactPermission('*'),
    ]);
    pod.node(
      cloud: eksCluster,
      containers: [ aws.containerTemplate(), tg.containerTemplate(), vault.containerTemplate()],
      volumes: [ sshVolume, awsVolume ]
    ) {

      if (branchName?.trim()) {
        repo=gitScm.checkout()
      } else {
        gitScm.checkout(repo)
      }

      println repo

      String deployEnvKey = "${repo.organization}/${repo.name}/${deployEnv}"
      String bucket = aws.getDevopsBucket(account)
      String profile = aws.getProfile(account)

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

      stage('Create AWS Secrets') {
        if (config.secretsTemplate != null) {
          vault.container {
            def map = [:]
            map[config.secretsTemplate] = 'aws-kda-temp-secrets.json'
            secrets = vault.processTemplates(repo, deployEnv, map)
          }
          aws.container {
            try {
              sh(returnStdout: true, script: "aws secretsmanager describe-secret --secret-id secret/${deployEnvKey} --profile ${profile}")
              sh """
                aws secretsmanager tag-resource --secret-id secret/${deployEnvKey} --tags Key='wam:product-name',Value=kda Key='wam:git-organization',Value=$repo.organization Key='wam:git-repository',Value=$repo.name Key='wam:category',Value='aws-secrets' Key='wam:product-platform',Value='imm' Key='wam:environment',Value=$deployEnv --profile $profile
                aws secretsmanager update-secret --secret-id secret/${deployEnvKey}  --secret-string file://aws-kda-temp-secrets.json --profile $profile
              """
            } catch(ex) {
              println ex.getMessage()
              sh """
                aws secretsmanager create-secret --name secret/${deployEnvKey} --secret-string file://aws-kda-temp-secrets.json --tags Key='wam:product-name',Value=kda Key='wam:git-organization',Value=$repo.organization Key='wam:git-repository',Value=$repo.name Key='wam:category',Value=aws-secrets Key='wam:product-platform',Value='im-kda' Key='wam:environment',Value=$deployEnv --profile $profile
              """
            }
          }
        }
      }

      stage('Upload Jars to S3') {
        def kdaApps = config.kdaApps

        if (branchName?.trim()) {
          println "branchName is not null --> nonprod"
          if (kdaApps != null && !kdaApps.isEmpty()) {
            copyArtifacts(projectName: "${projectName}", selector: specific("${buildNumber}"))
          }
        } else {
          println "branchName is null --> prod"
          if (kdaApps != null && !kdaApps.isEmpty()) {
            print archiveMap
            for(kdaa in kdaApps) {
              def artifact = archiveMap[kdaa.flinkJarLocation]
              println "flinkJarLocation ->" + kdaa.flinkJarLocation + ", artifact ->" + artifact
              def artifactFilename
              def token = artifact.split('/')
              for(t in token) {
                artifactFilename = t
              }
              println artifactFilename
              copyRemoteArtifacts(from: "jenkins://b01399c8b0598a55636663fe598c6d09/${upstreamJobName}",
                includes: "${artifact}",
                selector: [$class: 'SpecificRemoteBuildSelector', number: "${upstreamBuildNumber}"],
                timeout: '1h 2m 20s');
              sh """
                pwd
                ls -la
                mkdir -p ./$kdaa.flinkJarLocation
                mv ./$artifactFilename ./$kdaa.flinkJarLocation/$artifactFilename
                ls -la
                ls -la ./$kdaa.flinkJarLocation
              """
            }
          }
        }

        for (kdaApp in kdaApps) {
          println kdaApp.toString()
          String flinkJarLocation = kdaApp.flinkJarLocation
          println flinkJarLocation

          if (kdaApp.enabled) {
            println archiveMap
            def artifact = archiveMap[kdaApp.flinkJarLocation]
            println artifact
            def token = artifact.split('/')
            for(t in token) {
              artifact = t
            }
            println artifact

            //adding the buildnumber
            def artifactNew = artifact.replace(".jar", "-${buildNumber}.jar")
            println "before upload it to S3 after adding the build number, the artifactNew name ->" + artifactNew

            String s3Key =(kdaApp.flinkJarLocation.contains('/'))? "${repo.organization}/${repo.name}/" + kdaApp.flinkJarLocation.split('/')[0] + "/${deployEnv}/${artifactNew}": "${repo.organization}/${repo.name}/" + "/${deployEnvKey}/${artifactNew}"
            aws.container {
              sh """
                aws s3 cp ./$flinkJarLocation/$artifact s3://$bucket/kda/$s3Key --profile $profile
              """
            }
          } else {
            println "find the kdaApp is not ENABLED!!!"
          }
        }
      }

      stage('Create/Update KDA App') {
        println repo
        def tfBaseFolder = null
        def tfStateName = "${repo.organization}-${repo.name}-${deployEnv}"
        def repoDependencies = config.dependencies
        if (repoDependencies != null) {
          for (mod in repoDependencies) {
            kdaTGProcessing(tfStateName, null, deployEnv, account, repo, null, "=", mod, false, profile)
          }
        }
        for (kdaApp in config.kdaApps) {
          if (kdaApp.enabled) {
            def appFolderName = (kdaApp.flinkJarLocation.contains('/'))?kdaApp.flinkJarLocation.split('/')[0]:null
            def applicationName = (appFolderName!=null)?"${repo.organization}-${repo.name}-${appFolderName}-${deployEnv}":"${repo.organization}-${repo.name}-${deployEnv}"
            String flinkJarLocation = kdaApp.flinkJarLocation
            print flinkJarLocation
            def artifact = sh(returnStdout: true, script: "ls ${flinkJarLocation}").trim()

            //adding the build bumber

            def artifactNew = artifact.replace(".jar", "-${buildNumber}.jar")
            println "after adding the build number, the artifact name became ->" + artifactNew

            def kdaDescribe = isKdaAppExist(applicationName, profile)
            print kdaDescribe
            try {
              def dependencies = kdaApp.dependencies
              if (dependencies != null) {
                for (mod in dependencies) {
                  kdaTGProcessing (applicationName, appFolderName, deployEnv, account, repo, artifactNew, "=", mod, false, profile)
                }
              }
              def props
              def appArn
              def cloudWatchLoggingOptionId
              def kdaGroups
              if (kdaDescribe == null) {
                //create new app using terrafrom KDA module
                kdaTGProcessing (applicationName, appFolderName, deployEnv, account, repo, artifactNew, "=", "kda", false, profile)
                kdaStartApplication(applicationName, profile)
                def newApp = isKdaAppExist(applicationName, profile)
                props = readJSON text: newApp
                appArn = props['ApplicationDetail']['ApplicationARN']
              } else {
                //untagging the kda application
                props = readJSON text: kdaDescribe
                appArn = props['ApplicationDetail']['ApplicationARN']
                kdaGroups = props['ApplicationDetail']['ApplicationConfigurationDescription']['EnvironmentPropertyDescriptions']['PropertyGroupDescriptions']

                cloudWatchLoggingOptionId = props['ApplicationDetail']['CloudWatchLoggingOptionDescriptions']['CloudWatchLoggingOptionId'][0]

                print "CloudWatchLoggingOptionId => " + cloudWatchLoggingOptionId
                untaggingKda(appArn, appFolderName, deployEnv, profile)
                def reset = (kdaApp.resetFlag != null && kdaApp.resetFlag)? true : false
                if (reset) {
                  kdaTGProcessing (applicationName, appFolderName, deployEnv, account, repo, artifactNew, "=", "kda", true, profile, kdaGroups)
                  //if this is a service type, start the application, otherwise keep it as ready status
                  def appType = kdaApp.appType
                  if (appType == 'service') {
                    kdaStartApplication(applicationName, profile)
                  }
                } else {

                  //**** not to generate the log stream for now
                  //def logStreamArn = createNewCloudWatchLoggingStream(repo, buildNumber, appFolderName, deployEnv, profile)
                  //kdaAwscliProcessing(appFolderName, kdaDescribe, deployEnv, account, repo, artifactNew, ":", profile, logStreamArn, cloudWatchLoggingOptionId, kdaGroups)
                  //****
                  kdaAwscliProcessing(appFolderName, kdaDescribe, deployEnv, account, repo, artifactNew, ":", profile, null, null, kdaGroups)
                }
              }
              //tagging the kda application
              taggingKda(appArn, appFolderName, deployEnv, repo.organization, repo.name, profile)
              //check the application status
              checkAppStatus(applicationName, profile)
              //send slack message
              slack.sendSlackMessage(applicationName, "KDA", repo.branch, buildNumber, userId, repo.commit, deployEnv, null)
            } catch(exception) {
              slack.sendSlackMessage(applicationName, "KDA", repo.branch, buildNumber, userId, repo.commit, deployEnv, exception.getMessage())
              error(exception.getMessage())
            }
          }
        }
      }
    }
}

def getGrouId(props) {
  def list = props['ApplicationDetail']['ApplicationConfigurationDescription']['EnvironmentPropertyDescriptions']['PropertyGroupDescriptions']
  def groupId
  for(item in list) {
    //print item
    def map = item['PropertyMap']
    //print map
    map.each{ k, v ->
      //print 'k=>' + k + ', v=>' + v
      if (k == 'group.id') {
        groupId = v
      }
    }
  }
  return groupId
}

def createNewCloudWatchLoggingStream(repo, buildNumber, appFolderName, deployEnv, profile) {
  def aws = new Aws()
  def logGroupName = "kda-flink/${repo.organization}/${repo.name}/${appFolderName}/${deployEnv}"
  def logGroupStreamName = "${repo.organization}-${repo.name}-${appFolderName}-${deployEnv}-log-stream-${buildNumber}"
  println "logGroupName->" + logGroupName
  println "logGroupStreamName->" + logGroupStreamName
  def logStreamArn
  aws.container {
    def status = sh(returnStatus: true, script: "aws logs describe-log-streams --log-group-name ${logGroupName} --profile ${profile} | grep ${logGroupStreamName}")
    if (status < 0 || status > 0) {
      print 'creating the new log stream -> ' + logGroupStreamName
      sh(returnStdout: true, script: "aws logs create-log-stream --log-group-name ${logGroupName} --log-stream-name ${logGroupStreamName}  --profile ${profile}")
      logStreamArn = sh(returnStdout: true, script: "aws logs describe-log-streams --log-group-name ${logGroupName} --profile ${profile} | grep arn | grep ${logGroupStreamName}")
      print logStreamArn
      logStreamArn = logStreamArn.trim().replace('"arn":', '').replaceAll('"', '').replaceAll(',', '').trim()
      print logStreamArn
    }
  }
  return logStreamArn
}

def untaggingKda(appArn, appFolderName, deployEnv, profile) {

  def aws = new Aws()
  def keys = "wam:git-organization wam:git-repository wam:environment"
  def envMap = getDeployEnvMap(null, appFolderName, null, deployEnv, false)
  def tagMaps = envMap['tags']
  print tagMaps
  if (tagMaps != null) {
    tagMaps.each { k, v ->
      if (v != null) {
        keys = keys + " " + k
      }
    }
  }
  print "untag keys -> " + keys
  aws.container {
    sh """
      aws kinesisanalyticsv2 untag-resource --resource-arn $appArn --tag-keys $keys --profile $profile
    """
  }
}

def taggingKda(appArn, appFolderName, deployEnv, organization, repository, profile) {
  def aws = new Aws()
  def keyValues = "Key=wam:git-organization,Value=${organization} Key=wam:git-repository,Value=${repository} Key=wam:environment,Value=${deployEnv}"
  def envMap = getDeployEnvMap(null, appFolderName, null, deployEnv, false)
  def tagMaps = envMap['tags']
  print tagMaps
  if (tagMaps != null) {
    tagMaps.each { k, v ->
      if (v != null) {
        keyValues = keyValues + " Key=${k},Value=${v}"
      }
    }
  }
  print "tag keyValues -> " + keyValues
  aws.container {
    sh """
      aws kinesisanalyticsv2 tag-resource --resource-arn $appArn --tags $keyValues --profile $profile
    """
  }
}

def getDeployEnvMap(envFileLocation, appFolderName, envFileName, deployEnv, isGlobalTG) {
  def envMap = [:]
  def envMapForEnvTemplate = [:]
  def envLocation = (envFileLocation!=null)? envFileLocation: ((appFolderName!=null)?"${workspace}/" + appFolderName + "/conf/env":"${workspace}/conf/env")
  def envFN = (envFileName!=null)?envFileName : deployEnv
  println envLocation
  println envFN

  sh """
    pwd
    ls -la
    ls -la $envLocation
  """

  Boolean isEnvTemplated = true
  //adding templating logic for all the env.groovy file, the generated env.groovy file will be located at the project root.
  def envTemplate = (envFileLocation!=null)? (envFileLocation + "/env.template"): ((appFolderName!=null)?"${workspace}/" + appFolderName + "/conf/env.template":"${workspace}/conf/env.template")
  if (fileExists("${envTemplate}")) {
    echo "Yes, ${envTemplate} exists"
    isEnvTemplated = true
  } else {
    echo "No, ${envTemplate} is NOT EXISTS"
    isEnvTemplated= false
  }

  println "!isGlobalTG && isEnvTemplated ->" + (!isGlobalTG && isEnvTemplated)

  if (!isGlobalTG && isEnvTemplated) {
    //load the env map
    if (fileExists("${envLocation}/${envFN}.groovy")) {
      echo "Yes, ${envLocation}/${envFN}.groovy exists"
      def tempScript = load "${envLocation}/${envFN}.groovy"
      envMapForEnvTemplate = tempScript.getEnvMap()
    } else {
      echo "No, ${envLocation}/${envFN}.groovy is NOT EXISTS"
    }
    //process the env template
    def fileContents = readFile("${envTemplate}")
    print fileContents
    def result = applyEnvMap(fileContents, envMapForEnvTemplate)

    writeFile file: "${workspace}/${deployEnv}.groovy", text: result
    sh """
      cat ${workspace}/${deployEnv}.groovy
    """
    //load this generate groovy file and process it
    if (fileExists("${workspace}/${deployEnv}.groovy")) {
      echo "Yes, ${workspace}/${deployEnv}.groovy exists"
      def tempScript = load "${workspace}/${deployEnv}.groovy"
      envMap = tempScript.getEnvMap()
    } else {
      echo "No, ${workspace}/${deployEnv}.groovy is not exists"
    }

    //adding override logic here
    def override = envMapForEnvTemplate.override
    if (override) {
      println override
      override.each{k, v ->
        if (v instanceof Map) {
          v.each { k1, v1 ->
            //println k1
            //println v1
            if (v1 instanceof Map) {
              v1.each { k2, v2 ->
                envMap[k][k1][k2] = v2
              }
            } else {
              envMap[k][k1] = v1
            }
          }
        } else {
          envMap[k] = v
        }
      }
    }

  } else {
    if (fileExists("${envLocation}/${envFN}.groovy")) {
      echo "Yes, ${envLocation}/${envFN}.groovy exists"
      def tempScript = load "${envLocation}/${envFN}.groovy"
      envMap = tempScript.getEnvMap()
    } else {
      echo "No, ${envLocation}/${envFN}.groovy is not exists"
    }
  }

  return envMap
}

def kdaTGProcessing (applicationName, appFolderName, deployEnv, account, repo, artifact, delimiter, module, resetFlag, profile) {
  kdaTGProcessing (applicationName, appFolderName, deployEnv, account, repo, artifact, delimiter, module, resetFlag, profile, null)
}

def kdaTGProcessing (applicationName, appFolderName, deployEnv, account, repo, artifact, delimiter, module, resetFlag, profile, kdaGroups) {
  def aws = new Aws()
  sh """
    ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git clone git@github.westernasset.com:shared-services/aws-common-terraform.git'
  """

  def envFileLocation = (appFolderName!=null)?null: "${workspace}/conf"
  def envFileName = (appFolderName!=null)?null: module
  def isGlobalTG = (appFolderName==null)

  def paramMap = getParameterMap(deployEnv, envFileLocation, envFileName, appFolderName, account, repo, artifact, delimiter, null, null, kdaGroups, isGlobalTG)

  //read the terragrunt template file
  def fileContents = readFile("${workspace}/aws-common-terraform/$module/terragrunt.template")
  def result = applyEnvMap(fileContents, paramMap)
  writeFile file: "${workspace}/aws-common-terraform/$module/terragrunt.hcl", text: result

  sh """
    cd $workspace/aws-common-terraform/$module
    mkdir -p $applicationName
    mv terragrunt.hcl $applicationName/terragrunt.hcl
    mv $account/env.hcl $applicationName/env.hcl
    ls -la $applicationName
    cat $applicationName/terragrunt.hcl
  """
  container('tg') {
    if (resetFlag) {
      sh """
        terragrunt --version
        cd $workspace/aws-common-terraform/kda/$applicationName
        terragrunt init
        terragrunt destroy -auto-approve -no-color
      """
      while(isKdaAppExist(applicationName, profile) != null) {
        sleep(time: 10, unit: "SECONDS")
      }
    }
    sh """
      terragrunt --version
      cd $workspace/aws-common-terraform/$module/$applicationName
      ls -la
      terragrunt init
      terragrunt plan -no-color
      terragrunt apply -auto-approve -no-color
      rm -rf $workspace/aws-common-terraform
    """
  }
}

def kdaStartApplication(applicationName, profile) {
  def aws = new Aws()
  aws.container {
    sh """
      aws kinesisanalyticsv2 start-application --application-name $applicationName  --run-configuration {} --profile $profile
    """
  }
}

def kdaAwscliProcessing(appFolderName, kdaDescribe, deployEnv, account, repo, artifact, delimiter, profile, logStreamArn, cloudWatchLoggingOptionId, kdaGroups) {
  def aws = new Aws()
  def props = readJSON text: kdaDescribe
  def applicationName = props['ApplicationDetail']['ApplicationName']
  def ApplicationVersionId = props['ApplicationDetail']['ApplicationVersionId']
  def ApplicationARN = props['ApplicationDetail']['ApplicationARN']
  def status = props['ApplicationDetail']['ApplicationStatus']
  println ApplicationARN
  println ApplicationVersionId

  //update the KDA app using the AWS CLI
  def paramMap = getParameterMap(deployEnv, null, null, appFolderName, account, repo, artifact, delimiter, logStreamArn, cloudWatchLoggingOptionId, kdaGroups, false)
  paramMap.CurrentApplicationVersionId = Integer.toString(ApplicationVersionId)
  print paramMap

  //read the terragrunt template file
  def kdaTemplateLocation = (appFolderName!=null)? "${appFolderName}/conf":"conf"
  def fileContents = readFile("${kdaTemplateLocation}/kda.template")
  print fileContents
  def result = applyEnvMap(fileContents, paramMap)
  writeFile file: "${kdaTemplateLocation}/kda.json", text: result
  sh """
    cat ${kdaTemplateLocation}/kda.json
  """

  def runConfigurationUpdateOptions = getRunConfigurationUpdateOptions(kdaTemplateLocation)

  def logRetention = paramMap.log_retention_in_days
  def logGrouName = "kda-flink/" + paramMap.ORG + "/" + paramMap.REPO + "/" + paramMap.kda_env_path
  //println "new logRetention ->" + logRetention + " for group name ->" + logGrouName

  aws.container {
    sh """
      aws logs put-retention-policy --retention-in-days $logRetention --log-group-name $logGrouName --profile $profile
      aws kinesisanalyticsv2 update-application --cli-input-json file://$kdaTemplateLocation/kda.json $runConfigurationUpdateOptions --profile $profile
    """
  }
}

def getRunConfigurationUpdateOptions(kdaTemplateLocation) {
  def runConfig = " "
  if (fileExists("${kdaTemplateLocation}/runConfig.json")) {
    runConfig = "--run-configuration-update file://$kdaTemplateLocation/runConfig.json"
  }
  return runConfig
}

def checkAppStatus(applicationName, profile) {
  def aws = new Aws()
  aws.container {
    def kdaDescribe=sh(returnStdout: true, script: "aws kinesisanalyticsv2 describe-application --application-name ${applicationName} --profile ${profile}")
    def props = readJSON text: kdaDescribe
    def status = props['ApplicationDetail']['ApplicationStatus']
    int i = 0
    while (status != "RUNNING" && status != "READY") {
      println "ApplicationStatus : " + status
      if (i > 120) {
        error("Taking too long to start the cluster .... ")
      }
      sleep(time: 30, unit: "SECONDS")
      i++
      kdaDescribe=sh(returnStdout: true, script: "aws kinesisanalyticsv2 describe-application --application-name ${applicationName} --profile ${profile}")
      props = readJSON text: kdaDescribe
      status = props['ApplicationDetail']['ApplicationStatus']
    }
  }
  print "Application deployment is done!!!"
}

def isKdaAppExist(applicationName, profile) {
  def aws = new Aws()
  def kdaDescribe = null
  try {
    aws.container {
      kdaDescribe=sh(returnStdout: true, script: "aws kinesisanalyticsv2 describe-application --application-name ${applicationName} --profile ${profile}")
    }
  } catch(exp) {
  }
  return kdaDescribe
}

def applyEnvMap(text, envMap) {
  envMap.each { k, v ->
    if (v != null && k != 'override') {
      text = text.replaceAll('\\$\\{' + k.toString() + '\\}', v)
    }
  }
  return text
}

def getS3Suffix(account) {
  def map = ["aws-sandbox":"s",
             "aws-nonprod":"n",
             "aws-prod":"x"]
  return map[account]
}

def getVPC(account) {
  def map = ["aws-sandbox":"vpc-0688ce5da7a4dab87",
             "aws-nonprod":"vpc-0ba414c7f4f14e407",
             "aws-prod":"vpc-05ecbda1595a425af"]
  return map[account]
}

def getAccountNumber(account) {
  def map = ["aws-sandbox":"204071211944",
             "aws-nonprod":"925005374023",
                "aws-prod":"300650491316"]
  return map[account]
}

def getSecurityGroup(account) {
  def map = ["aws-sandbox":"sg-0bcad7295f40edf5f",
             "aws-nonprod":"sg-09ff6ec042c0c7990",
                "aws-prod":"sg-08eea48de614b2591"]
  return map[account]
}

def getVPCSubnets(account) {
  def map = ["aws-sandbox":["subnet-043aa521e5d151dcc",
                            "subnet-006813cdfc04d57b2",
                            "subnet-00dfb5d4eb57622c2",
                            "subnet-0ca0520196b8ec79d"],
             "aws-nonprod":["subnet-002dd27c2386076d2",
                            "subnet-0912eb2e4723ba873",
                            "subnet-074091e6c749b03d2",
                            "subnet-01b080eed9f2585cf"],
             "aws-prod":["subnet-0ef1a2da3db35195a",
                         "subnet-0dac79044dadc7ab5",
                         "subnet-06023dc23ed225dbc",
                         "subnet-0d5c020a2c508ba2d"]]
  return map[account]
}

def getParameterMap(deployEnv, envFileLocation, envFileName, appFolderName, account, repo, artifact, delimiter, logStreamArn, cloud_watch_logging_option_id, kdaGroups, isGlobalTG) {

  def envMap = getDeployEnvMap(envFileLocation, appFolderName, envFileName, deployEnv, isGlobalTG)

  def kda_env_name = (appFolderName!=null)?appFolderName+"-"+deployEnv:deployEnv

  envMap.ORG = repo.organization
  envMap.REPO = repo.name
  envMap.ENV = deployEnv
  envMap.kda_env_path = (appFolderName!=null)?appFolderName+"/"+deployEnv:deployEnv
  envMap.kda_env_name = kda_env_name

  if (cloud_watch_logging_option_id!=null) {
    envMap.cloud_watch_logging_option_id = cloud_watch_logging_option_id
  }

  if (logStreamArn != null) {
    envMap.log_stream_arn_update = logStreamArn
  }

  envMap.vpc_id = getVPC(account)
  envMap.account_number = getAccountNumber(account)
  envMap.subnet_ids = getVPCSubnets(account)
  envMap.security_group_id = getSecurityGroup(account)
  if (envMap['tags'] == null) {
    envMap['tags'] = [:]
  }
  envMap['tags']["wam:git-organization"]=repo.organization
  envMap['tags']["wam:git-repository"]=repo.name
  envMap['tags']["wam:environment"]=deployEnv
  envMap.S3_SUFFIX = getS3Suffix(account)
  envMap.kda_flink_jar = artifact

  envMap.each{ key, val ->
    if (val instanceof Map) {
      boolean isMap = false
      val.each{ k, v ->
        if (v instanceof Map) {
          isMap = true
        }
      }
      if (isMap) {
        int i = 0
        val.each { k1, v1 ->
          String pkey = "app_parameters_group_id"
          String pVal = "app_parameters"
          if (i > 0) {
            pkey = "app_parameters_group_id_" + i
            pVal = "app_parameters_" + i
          }
          envMap[pkey] = k1
          //check the group is NOT VOLATILE by checking the group name which end with _vn
          def replaceMap
          if (k1.endsWith('_nv')) {
            replaceMap = getNVgroup(kdaGroups, k1)
          }
          replaceMap = (replaceMap==null)? v1: replaceMap
          envMap[pVal] = getMapString(replaceMap, delimiter)
          i++
        }
        print "total -> " + i + " kda parameter groups!"
        while (i < 10) {
          envMap["app_parameters_group_id_" + i] = "kda_default_group_" + i
          envMap["app_parameters_" + i] = "{\"key\" : \"value\"}"
          i++
        }
      } else {
        envMap[key] = getMapString(val, delimiter)
      }
    } else if(val instanceof List) {
      def item = val.first()
      if (item instanceof String) {
        envMap[key] = getListString(val)
      } else {
        if (key == "dynamodb_tables") {
          envMap["dynamodb_pay_per_request_list"] = getListDynamodbOnDemand(val, deployEnv)
          envMap["dynamodb_provisioned_list"] = getListDynamodbProvisioned(val, deployEnv)
          envMap[key] = null
        } else {
          envMap[key] = getListObject(val)
        }
      }
    }
  }

  return envMap
}

def getNVgroup(kdaGroups, v) {
  def map = null
  kdaGroups.each { item ->
    def key = item.PropertyGroupId
    def val = item.PropertyMap
    //println key
    //println v
    if (key == v) {
      //println "FIND IT !!!!!!!!!!!!!!!!!!!!!!"
      map = val
    }
  }
  return map
}

def getMapString(map, delimiter) {
  String str = ""
  str = "{\n"
  int i = 0
  map.each{ key, val ->
    def v = val
    str = (i>0)? str + ",\n" : str
    str = str +  "\"" + key + "\"" + delimiter +  "\"" + v + "\""
    i++
  }
  str = str + "\n}\n"
  print str
  return str
}

def getListString(list) {
  String str = "["
  int i=0
  list.each { it ->
    str = (i>0)? str + ",":str
    str = str + "\"" + it + "\""
    i++
  }
  str = str + "]"
  return str
}

def getListObject(list) {
  String str = "{"
  int i=0
  String segment = "item" + i + "={"
  for (item in list) {
    item.each { k, v ->
      if (v instanceof String) {
        segment = segment + k + "=" + "\"" + v + "\"\n"
      } else {
        segment = segment + k + "=" + v + "\n"
      }
    }
    str = str + " " + segment + "}"
    i++
    segment = ", \nitem" + i + "={"
  }
  str = str + "}"
  return str
}

def getListDynamodbOnDemand(list, deployEnv) {

  String str = "{"
  int i=0
  String segment = "item" + i + "={"
  for (item in list) {
    if (item.billing_mode == null) {
      item.each { k, v ->
        def vv = (k == "table_name")? deployEnv + "_" + v : v
        if (v instanceof String) {
          segment = segment + k + "=" + "\"" + vv + "\"\n"
        } else {
          segment = segment + k + "=" + v + "\n"
        }
      }
      str = str + " " + segment + "}"
      i++
      segment = ", \nitem" + i + "={"
    }
  }
  str = str + "}"
  return str

}

def getListDynamodbProvisioned(list, deployEnv) {

  String str = "{"
  int i=0
  String segment = "item" + i + "={"
  for (item in list) {
    if (item.billing_mode != null) {
      item.each { k, v ->
        def vv = (k == "table_name")? deployEnv + "_" + v : v
        if (v instanceof String) {
          segment = segment + k + "=" + "\"" + vv + "\"\n"
        } else {
          segment = segment + k + "=" + v + "\n"
        }
      }
      str = str + " " + segment + "}"
      i++
      segment = ", \nitem" + i + "={"
    }
  }
  str = str + "}"
  return str

}

return this
