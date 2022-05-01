package com.aristotlecap.pipeline.operationRelease;

def build(projectType, gitBranchName, buildNumber, builderTag, prodAccounts,
          releaseVersion, templateFile, stackName, organizationName, appGitRepoName,
          gitScm, gitCommit, crNumber) {

  stage("Should I deploy to PROD?") {
    checkpoint "Deploy To Prod"

    def didTimeout = false
    def userInput

    currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: 'Approve Release?')
      }
    } catch(err) { // timeout reached or input false
      didTimeout = true
    }
    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else {
      deploy(projectType, gitBranchName, buildNumber, builderTag, prodAccounts,
             releaseVersion, templateFile, stackName, organizationName, appGitRepoName,
             gitScm, gitCommit, crNumber)
    }
  }
}

def deploy(projectType, gitBranchName, buildNumber, builderTag, prodAccounts,
           releaseVersion, templateFile, stackName, organizationName, appGitRepoName,
           gitScm, gitCommit, crNumber) {

  def commons = new com.aristotlecap.pipeline.Commons()

  def samBuilderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  echo builderTag

  podTemplate(
    cloud: 'sc-production',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'sam', image: "${samBuilderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-prod", mountPath: '/home/jenkins/.ssh'),
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws'),
      persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws2')
  ])  {
    node(POD_LABEL) {
      try {
        println 'inside the deployment logic'

        currentBuild.displayName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"

        echo currentBuild.displayName

        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        def s3bucket = 'wa-devops-x'
        def profileName = 'prod'

        stage ('Build') {
          container('sam') {
            sh """
              sam build
            """
          }
        }

        stage ('Package') {
          container('sam') {
            sh """
              sam package --output-template-file $workspace/$templateFile --s3-bucket $s3bucket --profile $profileName
              ls -la
              cat $workspace/$templateFile
            """
          }
        }

        def gitReleaseTagName = "${gitBranchName}-${releaseVersion}-${buildNumber}-${crNumber}"
        sh """
          git config --global user.email "jenkins@westernasset.com"
          git config --global user.name "Jenkins Agent"
          git config --global http.sslVerify false
          git config --global push.default matching
          git config -l

          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}"'
          ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
        """

        //echo sh(script: 'env|sort', returnStdout: true)
        stage('Deploy to Prod') {
          string parameterOpts = commons.getSamParameterList(profileName)
          container('sam') {
            sh """
              sam deploy --template-file $workspace/$templateFile --stack-name $stackName \
                  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
                  --region us-west-2 --profile $profileName $parameterOpts \
                  --tags organization=$organizationName \
                  --tags application=$appGitRepoName
                  
              aws cloudformation describe-stacks --stack-name $stackName --region us-west-2 --query "Stacks[].Outputs"  --profile $profileName
            """
          }
        }
      } catch(err) {
        println err
      }
    }
  }
}
