package com.aristotlecap.pipeline.util

import groovy.text.*
import java.util.regex.Matcher

def gitCloneRepo(repo) {
  if (repo?.trim() && !repo.equalsIgnoreCase("local")){
    //clone the repo
    sh """
      ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git clone $repo'
      ls -ls
    """
  }
}

def components(component, componentType, cloudProfile, gitRepo) {
  def componentRepoRoot = component.repoRoot
  gitCloneRepo(component.repo)
  for(stack in component.stacks) {
    print stack
    if (stack.account.equalsIgnoreCase(cloudProfile) && stack.enabled) {
      processingStack(componentType, stack, cloudProfile, component.repoRoot, gitRepo, stack.env)
    }
  }
  if (componentRepoRoot?.trim() && !component.repo.equalsIgnoreCase("local")) {
    sh """
      rm -rf $workspace/$componentRepoRoot
    """
  }
}

def processingStack(componentType, stack, cloudProfile, repoRoot, gitRepo, stackEnv) {
  for(template in stack.templates) {
    print template
    if(template.enabled) {
      if (componentType.equalsIgnoreCase('cdk')) {
        cdkTemplateProcessing(template, cloudProfile, repoRoot, gitRepo, stackEnv)
      } else if (componentType.equalsIgnoreCase('sam')) {
        samTemplateProcessing(template, cloudProfile, repoRoot, gitRepo, stackEnv)
      } else if (componentType.equalsIgnoreCase('tf')) {
        tfTemplateProcessing(template, cloudProfile, repoRoot, gitRepo)
      } else if (componentType.equalsIgnoreCase('tg')) {
        tgTemplateProcessing(template, cloudProfile, repoRoot, gitRepo)
      } else if (componentType.equalsIgnoreCase('eksctl')) {
        eksctlTemplateProcessing(template, cloudProfile, repoRoot, gitRepo)
      }
    }
  }
}

def cdkTemplateProcessing(template, cloudProfile, repoRoot, gitRepo, stackEnv) {
  if (stackEnv?.trim()) {
    def confFileURI = template.confFileURI
    for(f in template.copyResources) {
      def localResource = f.localResource
      def remoteResource = f.remoteResource
      processEnvTemplate(stackEnv, null, localResource, confFileURI)
      sh """
        cp $workspace/$localResource $workspace/$repoRoot/$remoteResource
        cat $workspace/$repoRoot/$remoteResource
      """
    }
  }
  def fileName = "${template.templateFile}"
  def templateFile = "bin/${cloudProfile}/${fileName}.js"
  print templateFile
  def stacks = template.stacks
  print stacks
  def organizationName = gitRepo.organizationName
  def appGitRepoName = gitRepo.appGitRepoName
  def commons = new com.aristotlecap.pipeline.Commons()
  container('cdk') {
    for(stack in stacks) {
      print stack
      try {
        sh """
          cp /home/jenkins/.npm/.npmrc /home/jenkins/.npmrc
        """
        sh """
          cd $repoRoot
          npm install
          npm run build
          cdk synth $stack --app $templateFile --profile $cloudProfile
        """
        try {
         sh """
            cd $repoRoot
            cdk diff $stack --app $templateFile --profile $cloudProfile
          """
        } catch(e) {
          println e.getMessage()
        }
        sh """
          cd $repoRoot
          cdk deploy $stack --app $templateFile --require-approval never --profile $cloudProfile
        """
      } catch(e) {
        print e
        error e.getMessage()
      }
    }
  }
}

def samTemplateProcessing(template, cloudProfile, repoRoot, gitRepo, stackEnv) {
  def s3bucket = 'wa-devops-n'
  if (cloudProfile == 'sandbox') {
    s3bucket = 'wa-devops-s'
  } else if (cloudProfile == 'prod') {
    s3bucket = 'wa-devops-x'
  }
  print 'stackEnv -->' + stackEnv
  print 's3bucket -->' + s3bucket

  def templateRootURI = template.templateRootURI
  def confFileURI = template.confFileURI
  def temp_yml = template.templateFile
  if (confFileURI?.trim()) {
    processEnvTemplate(stackEnv, templateRootURI, temp_yml, confFileURI)
  }
  def stackName = template.samStack
  def optionalNodeRoot = template.optionalNodeRoot
  container('sam') {
    if (optionalNodeRoot?.trim()) {
      sh """
        cd $optionalNodeRoot
        npm install
        ls -la
        pwd
      """
    }
    sh """
      cd $templateRootURI
      ls -la
      pwd
      sam build -t ./$temp_yml
      sam package --output-template-file ./package.yaml -t ./$temp_yml --s3-bucket $s3bucket --s3-prefix sam --profile $cloudProfile
      sam deploy --template-file ./package.yaml --stack-name $stackName \
          --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
          --no-fail-on-empty-changeset \
          --region us-west-2 --debug --profile $cloudProfile
      aws cloudformation describe-stacks --stack-name $stackName --region us-west-2 --query "Stacks[].Outputs"  --profile $cloudProfile
    """
  }
}

def processEnvTemplate(env, templateRootURI, templateFile, confFileURI) {
  if (fileExists("${workspace}/${confFileURI}/${env}.groovy")) {
    echo "Yes, ${workspace}/${confFileURI}/${env}.groovy exists"
    def tempScript = load "${workspace}/${confFileURI}/${env}.groovy"
    envMap = tempScript.getEnvMap()
    def tempFile = (templateRootURI?.trim())? "${templateRootURI}/${templateFile}":"${templateFile}";
    echo 'working here'
    print tempFile
    if(fileExists("${workspace}/${tempFile}")) {
      def fileContents = readFile("${workspace}/${tempFile}")
      print fileContents
      def result = applyEnvMap(fileContents, envMap)
      writeFile file: "${workspace}/${tempFile}", text: result
      sh """
        cat $workspace/$tempFile
      """
    } else {
      error "${workspace}/${tempFile} not exist!!!!"
    }
  } else {
    echo "No, ${workspace}/${confFileURI}/${env}.groovy does not exist"
  }
}

def applyEnvMap(text, envMap) {
  envMap.each { k, v ->
    text = text.replaceAll('\\$\\{' + k.toString() + '\\}', Matcher.quoteReplacement(v))
  }
  return text
}

def tfTemplateProcessing(template, cloudProfile, repoRoot, gitRepo) {
  print template
  def templateUri = template.templateURI
  container('tf') {
    withEnv(["AWS_PROFILE=${cloudProfile}"]) {
      //plan
      sh """
        cd $templateUri
        pwd
        set
        terraform --version
        ls -la
        terraform init
        terraform plan -no-color
        terraform apply -auto-approve -no-color
      """
    }
  }
}

def tgTemplateProcessing(template, cloudProfile, repoRoot, gitRepo) {
  print template
  def templateUri = template.templateURI
  container('tg') {
    withEnv(["AWS_PROFILE=${cloudProfile}"]) {
      //plan
      sh """
        set
        cd $templateUri
        pwd
        set
        terragrunt --version
        ls -la
        terragrunt init
        terragrunt plan -no-color
        terragrunt apply -auto-approve -no-color
      """
    }
  }
}

def eksctlTemplateProcessing(template, cloudProfile, repoRoot, gitRepo) {
  print template
  def templateUri = template.templateURI
  //cluster logic
  if (templateUri?.trim() && template.commandObject?.trim() && template.commandObject.equalsIgnoreCase("cluster")) {
    eksClusterProcessing(template, cloudProfile, repoRoot, gitRepo)
  } else if (templateUri?.trim() && template.commandObject.equalsIgnoreCase("nodegroup")) {
    eksNodeGroupProcessing(template, cloudProfile, repoRoot, gitRepo)
  } else if (templateUri?.trim() && template.commandObject.equalsIgnoreCase("iamserviceaccount")) {
    eksIamServiceAccountProcessing(template, cloudProfile, repoRoot, gitRepo)
  }
}

def eksClusterProcessing(template, cloudProfile, repoRoot, gitRepo) {
  def templateUri = template.templateURI
  def yaml = readYaml file: "${workspace}/${templateUri}"
  def metadata = yaml.metadata
  print metadata
  def clusterName = metadata.name
  print clusterName
  def commandObject = template.commandObject
  def commandAction = template.commandAction
  container('eksctl') {
    def bool = true
    def additionOptions = " --timeout 50m0s "
    if (commandAction.equalsIgnoreCase('create')) {
      try {
        sh(returnStdout: true, script: "aws eks describe-cluster --name ${clusterName} --profile ${cloudProfile}")
        print "The ${commandObject} with name ${clusterName} exist!!! Skip the Create!!!"
        bool = false
      } catch(exp1) {
        print exp1.getMessage()
      }
    } else {
      additionOptions = additionOptions + " --approve "
    }
    if (bool) {
      sh """
        eksctl $commandAction $commandObject -f ./$templateUri --profile $cloudProfile $additionOptions
      """
    }
  }
}

def eksNodeGroupProcessing(template, cloudProfile, repoRoot, gitRepo) {
  def templateUri = template.templateURI
  def yaml = readYaml file: "${workspace}/${templateUri}"
  def metadata = yaml.metadata
  print metadata
  def clusterName = metadata.name

  def nodeGroups = yaml.managedNodeGroups!=null?yaml.managedNodeGroups:yaml.nodeGroups
  def nodeGroupName = nodeGroups[0].name

  println nodeGroups
  println nodeGroupName

  print clusterName
  def commandObject = template.commandObject
  def commandAction = template.commandAction
  container('eksctl') {
    def bool = true
    def additionOptions = " --timeout 50m0s "
    if (commandAction.equalsIgnoreCase('create')) {
      try {
        sh """
          eksctl get nodegroup --name $nodeGroupName --cluster $clusterName --profile $cloudProfile  --output json
        """
        print "The ${commandObject} with name ${clusterName} exist!!! Skip the Create!!!"
        bool = false
      } catch(exp1) {
        print exp1.getMessage()
      }
    }
    if (bool) {
      sh """
        eksctl $commandAction $commandObject -f ./$templateUri --profile $cloudProfile $additionOptions
      """
    }
  }
}

def eksIamServiceAccountProcessing(template, cloudProfile, repoRoot, gitRepo) {
  def templateUri = template.templateURI
  def commandObject = template.commandObject
  def commandAction = template.commandAction
  container('eksctl') {
    def additionOptions = " --timeout 50m0s --approve"
    if (commandAction.equalsIgnoreCase('create')) {
      sh """
        eksctl delete $commandObject -f ./$templateUri --profile $cloudProfile $additionOptions
      """
    }
    sh """
      eksctl $commandAction $commandObject -f ./$templateUri --profile $cloudProfile $additionOptions
    """
  }
}
