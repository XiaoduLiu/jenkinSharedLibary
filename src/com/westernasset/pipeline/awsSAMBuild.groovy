package com.westernasset.pipeline;

def build(projectTypeParam, gitBranchName, buildNumber, builderTag, nonprodAccounts,
          prodAccounts, releaseVersion, templateFile, stackName) {
  def repo
  def commons = new com.westernasset.pipeline.Commons()
  def samBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  echo builderTag
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sam', image: "${samBuilderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
  ]){
    node(POD_LABEL) {
      repo = commons.clone()
      println "repo print"
      print repo
      println "repo print end"
      echo sh(script: 'env|sort', returnStdout: true)
      currentBuild.displayName  = "${gitBranchName}-${releaseVersion}-${buildNumber}"
      stage ('Build') {
        container('sam') {
          sh 'sam build'
        }
      }
    }
  }
  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonprodAccounts, false, false, false, currentBuild.displayName)
  if (gate.deployEnv != null) {
    nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber,builderTag, nonprodAccounts,
                       prodAccounts, releaseVersion, templateFile, stackName, repo.organizationName,
                       repo.appGitRepoName, repo.gitScm, repo.gitCommit, gate.deployEnv)

  }
}

def nonProdDeployLogic(projectTypeParam, gitBranchName, buildNumber,builderTag, nonprodAccounts,
                       prodAccounts, releaseVersion, templateFile, stackName, organizationName,
                       appGitRepoName, gitScm, gitCommit, deployEnv) {
  def commons = new com.westernasset.pipeline.Commons()
  def samBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  echo builderTag
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sam', image: "${samBuilderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
  ])  {
    node(POD_LABEL) {
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"
        //echo sh(script: 'env|sort', returnStdout: true)
        def s3bucket = 'wa-devops-n'
        def profileName = 'nonprod'
        if (deployEnv == 'sandbox') {
          s3bucket = 'wa-devops-s'
          profileName = 'sandbox'
        }
        def temp_yml = commons.processSAMTemplate(profileName)
        stage ('Package') {
          container('sam') {
            sh """
              sam build -t $workspace/$temp_yml
              sam package --output-template-file $workspace/$templateFile -t $workspace/$temp_yml --s3-bucket $s3bucket --s3-prefix sam --profile $profileName
              ls -la
              cat $workspace/$templateFile
            """
          }
        }
        stage('Deploy to nonprod') {
          string parameterOpts = commons.getSamParameterList(profileName)
          container('sam') {
            sh """
              sam deploy --template-file $workspace/$templateFile --stack-name $stackName \
                  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
                  --no-fail-on-empty-changeset \
                  --region us-west-2 --debug --profile $profileName $parameterOpts \
                  --tags organization=$organizationName \
                  --tags application=$appGitRepoName \
                  --tags environment=$stackName
              aws cloudformation describe-stacks --stack-name $stackName --region us-west-2 --query "Stacks[].Outputs"  --profile $profileName
            """
          }
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, true, false, currentBuild.displayName, 'Ready to Release?', 'Approve Release?')
  if (gate.destroyFlag) {
    destroy(projectTypeParam, gitBranchName, buildNumber,builderTag, nonprodAccounts,
                   prodAccounts, releaseVersion, templateFile, stackName, organizationName,
                   appGitRepoName, gitScm, gitCommit, deployEnv)
  } else if (gate.crNumber != null) {
    qaApprove(projectTypeParam, gitBranchName, buildNumber,builderTag, nonprodAccounts,
                  prodAccounts, releaseVersion, templateFile, stackName, organizationName,
                  appGitRepoName, gitScm, gitCommit, deployEnv,
                  gate.crNumber)
  }
}

def destroy(projectTypeParam, gitBranchName, buildNumber,builderTag, nonprodAccounts,
            prodAccounts, releaseVersion, templateFile, stackName, organizationName,
            appGitRepoName, gitScm, gitCommit, deployEnv) {
  def commons = new com.westernasset.pipeline.Commons()
  def samBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  echo builderTag
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}-destroy"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sam', image: "${samBuilderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
  ])  {
    node(POD_LABEL) {
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"
        container('sam') {
          sh """
            aws cloudformation delete-stack --stack-name $stackName  --profile $deployEnv
          """
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
}

def qaApprove(projectTypeParam, gitBranchName, buildNumber,builderTag, nonprodAccounts,
              prodAccounts, releaseVersion, templateFile, stackName, organizationName,
              appGitRepoName, gitScm, gitCommit, deployEnv,
              crNumber) {
  currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${deployEnv}-${crNumber}"
  stage('trigger downstream job') {
    build job: "${env.opsReleaseJob}", wait: false, parameters: [
      [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectTypeParam)],
      [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
      [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(crNumber)],
      [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
      [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
      [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
      [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
      [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
      [$class: 'StringParameterValue', name: 'prodAccounts', value: String.valueOf(prodAccounts)],
      [$class: 'StringParameterValue', name: 'builderTag', value: String.valueOf(builderTag)],
      [$class: 'StringParameterValue', name: 'templateFile', value: String.valueOf(templateFile)],
      [$class: 'StringParameterValue', name: 'stackName', value: String.valueOf(stackName)],
      [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)]
    ]
  }
}
