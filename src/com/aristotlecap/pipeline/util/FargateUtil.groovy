package com.aristotlecap.pipeline.util;

import groovy.text.SimpleTemplateEngine
import com.aristotlecap.pipeline.Commons

def localBuild(builderTag, buildSteps) {
  if (builderTag != 'null') {
    container('builder') {
      echo "EXECUTING LOCAL BUILD & TESTING STEPS"
      def workspace = sh(returnStdout: true, script: "pwd").trim()
      echo "${workspace}"
      for (step in buildSteps) {
        echo "step -> " + step
        sh """
          $step
        """
      }
      sh """
       ls -la
      """
    }
  }
}

def dockerBuild(imageDtrUri, imageRepoKey, releaseVersion, organizationName, appGitRepoName, gitBranchName, gitCommit, buildNumber) {
  def commons = new com.aristotlecap.pipeline.Commons()
  container('docker') {
    docker.withRegistry("https://${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_CREDENTIAL}") {
      echo "EXECUTE DOCKER BUILD & PUSH TO Artifactory"
      def image = "${organizationName}/${appGitRepoName}:${gitBranchName}-${releaseVersion}-${buildNumber}"
      def pasImage = "${imageDtrUri}/${imageRepoKey}/${organizationName}/${appGitRepoName}:${gitBranchName}-${releaseVersion}-${buildNumber}"
      def pasImageLatest = "${imageDtrUri}/${imageRepoKey}/${organizationName}/${appGitRepoName}:${gitBranchName}-${releaseVersion}-latest"
      def nowTimeStamp = new Date().format("yyyy-MM-dd HH:mm:ss'('zzz')'")
      commons.removeTag(pasImageLatest)
      sh """
        docker image build --tag $image --label com.aristotlecap.github.org=$organizationName  --label com.aristotlecap.github.repo=$appGitRepoName --label com.aristotlecap.github.branch=$gitBranchName --label com.aristotlecap.github.hash=$gitCommit --label com.aristotlecap.buildTime='$nowTimeStamp' .
        docker tag $image $pasImage
        docker push $pasImage
        docker tag $image $pasImageLatest
        docker push $pasImageLatest
      """
    }
  }
}

def awsDeployment(deployEnv, projectType, organizationName, appGitRepoName, budgetCode, imageTag, isDeployToProd) {
  def appGitScm = "git@github.westernasset.com:devops/wam-cdk-templates.git"
  def commons = new com.aristotlecap.pipeline.Commons()
  withCredentials([string(credentialsId: "${env.GHE_JENKINS_GIT_TOKEN}", variable: 'GIT_TOKEN')]){
    sh """
      mkdir $workspace/$deployEnv
      cd $workspace/$deployEnv
      id
      ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git clone $appGitScm'
      ls -la $workspace/$deployEnv/wam-cdk-templates/$projectType
    """
    def wk = "${workspace}/${deployEnv}/wam-cdk-templates/${projectType}"
    dir(wk) {
      def envMap = [:]
      if (fileExists("${workspace}/conf/cdk/${deployEnv}.groovy")) {
        echo "Yes, ${workspace}/conf/cdk/${deployEnv}.groovy exists"
        def tempScript = load "${workspace}/conf/cdk/${deployEnv}.groovy"
        envMap = tempScript.getEnvMap()
      }
      envMap.organization = "${organizationName}"
      envMap.repository = "${appGitRepoName}"
      envMap.environment = "${deployEnv}"
      envMap.budgetCode = "${budgetCode}"
      envMap.imageTag = imageTag

      //template processing to genreate the actual ts file
      def profile = (isDeployToProd)? 'prod':'nonprod'
      processingTokens(profile, wk, envMap)
      def stackName = envMap['stackName']
      println 'stackname ->' + stackName

      //cdk local Build
      container('cdk') {
        commons.setNpmrcFilelink()
        sh """
          pwd
          npm install
          npm run build
        """

        //deploy the fargate stack
        cdkDeploy(stackName, profile, organizationName, appGitRepoName, deployEnv, budgetCode)

        //figure out the ALB Name
        def albNameCmd = "aws cloudformation --region us-west-2 describe-stacks --stack-name ${stackName} --profile ${profile} --query \"Stacks[0].Outputs[?OutputKey=='ALBName'].OutputValue\" --output text"
        def albName = sh(script: "${albNameCmd}", returnStdout: true).trim()
        print 'albName = ' + albName

        def albCmd = "aws elbv2 describe-load-balancers --names ${albName} --profile ${profile}"
        def albString = sh(script: "${albDnsCmd}", returnStdout: true)
        print 'albString = ' + albString

        def albArn = sh(script: "cat $albString | jq '.LoadBalancers[0].LoadBalancerArn'", returnStdout: true).trim().replaceAll('\\/', '\\\\/')
        print 'albArn = ' + albArn

        def albDns = sh(script: "cat $albString | jq '.LoadBalancers[0].DNSName'", returnStdout: true).trim()
        print 'albDns = ' + albDns

        def albSg = sh(script: "cat $albString | jq '.LoadBalancers[0].SecurityGroups[0]'", returnStdout: true).trim()
        print 'albSg = ' + albSg

        def albZoneId = sh(script: "cat $albString | jq '.LoadBalancers[0].CanonicalHostedZoneId'", returnStdout: true).trim()
        print 'albZoneId = ' + albZoneId

        def dnsMap = [:]
        dnsMap.dnsStackName = envMap['dnsStackName']
        dnsMap.dnsName = envMap['dnsName']
        dnsMap.albArn = albArn
        dnsMap.albSg = albSg
        dnsMap.albZoneId = albZoneId
        dnsMap.albDns = albDns

        processingTokens('sharedservice', wk, dnsMap)

        sh """
          pwd
          ls -la
          npm run build
        """

        //deploy the dns stack
        cdkDeploy(envMap['dnsStackName'], 'sharedservice', organizationName, appGitRepoName, deployEnv, budgetCode)

      }
    }
  }
}

def processingTokens(profile, wk, envMap) {
  def templateFileName = "${profile}.templ"
  envMap.each { key, val ->
    def mykey = '\\$' + "${key}"
    def mval = val.replaceAll('\n', ' ')
    sh """
      sed -i 's/$mykey/$mval/g' $wk/bin/$templateFileName
    """
  }
  def tsFileName = "${profile}.ts"
  sh """
    cp $wk/bin/$templateFileName $wk/bin/$tsFileName
    ls -la $wk/bin
    cat $wk/bin/$tsFileName
  """
}

def templateProcessing(templateFileName, envMap, wk) {
  def stackContent = readFile("${wk}/bin/${templateFileName}")
  def engine = new groovy.text.SimpleTemplateEngine()
  def template = engine.createTemplate(stackContent).make(envMap)
  def resultString = template.toString();
  echo resultString

  writeFile file: "${wk}/bin/test.ts", text: resultString

  sh """
    ls -la $wk/bin
    cat $wk/bin/test.ts
  """
}

def cdkDeploy(stackName, profile, organizationName, appGitRepoName, deployEnv, budgetCode) {
  def ts = profile + '.js'
  sh """
    cdk synth $stackName --app bin/$ts --profile $profile
    cdk diff $stackName --app bin/$ts --profile $profile
    cdk deploy $stackName --app bin/$ts --require-approval never --tags organization=$organizationName --tags application=$appGitRepoName --tags environment=$deployEnv --tags budgetCode=$budgetCode  --profile $profile
  """
}

def releaseTriggerFlag(releaseFlag, deployEnv, qaEnvs) {
  def flag = false
  qaEnvs.each { qa ->
    if (qa == deployEnv) {
      flag = true
    }
  }
  return flag && releaseFlag
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
