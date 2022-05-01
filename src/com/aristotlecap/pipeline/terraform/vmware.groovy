package com.aristotlecap.pipeline.terraform;

def build(builderTag, organizationName, repoName, resourceName) {
  echo builderTag
  echo organizationName
  echo repoName
  echo resourceName
  def buildNumber
  def gitCommit

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'tf', image: "${builderImage}", ttyEnabled: true, command: 'cat')
  ]) {
    node(POD_LABEL) {
      // Clean workspace before doing anything
      deleteDir()
      try {
     	  stage ('Clone') {
           withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.GHE_JENKINS_CRED}",
             usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
             sh """
               git config --global user.email "jenkins@westernasset.com"
               git config --global user.name "Jenkins Agent"
               git config --global http.sslVerify false
               git config --global push.default matching
               git config -l
               git clone https://$USERNAME:$PASSWORD@github.westernasset.com/iac/terraform-vmware.git
               ls -la
               cd ./terraform-vmware
               ls -la
             """
             gitCommit=sh(returnStdout: true, script: "cd ./terraform-vmware && git log -n 1 --pretty=format:'%h'").trim()
             echo gitCommit

             def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
             echo workspace
           }
     	  }
        buildNumber = "${env.BUILD_NUMBER}"
        currentBuild.displayName = "${organizationName}-${repoName}-${resourceName}-${buildNumber}-plan"

        def wamDomainSecret = getDomainSecret(workspace, organizationName, repoName, resourceName)

        container('tf') {
          //sh 'set'
          //echo sh(script: 'env|sort', returnStdout: true)
          stage ('Terraform Plan') {
            withCredentials([usernamePassword(credentialsId:"${env.VSPHERE_CRED}", usernameVariable: 'USER', passwordVariable: 'PWD'),
                             usernamePassword(credentialsId: "${wamDomainSecret}", usernameVariable: 'DOMAIN_USERNAME', passwordVariable: 'DOMAIN_PASSWORD'),
                             usernamePassword(credentialsId: "${env.AWS_WAMCO_CRED}", usernameVariable: 'ACCESS_KEY', passwordVariable: 'SECRET_KEY')]) {
              sh """
                ls -la
                cd ./terraform-vmware/${organizationName}/${repoName}/${resourceName}
                ls -la
                terraform init -backend-config="access_key=${ACCESS_KEY}" -backend-config="secret_key=${SECRET_KEY}"
                terraform plan -var vsphere_user=${USER} -var vsphere_password=${PWD} -var domainjoin_admin_user=${DOMAIN_USERNAME} -var domainjoin_admin_password=${DOMAIN_PASSWORD}
              """
            }
          }
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  terraformVsphereCheckPoint('apply', buildNumber, builderTag, organizationName, repoName, resourceName, gitCommit)
}

def getDomainSecret(workspace, organizationName, repoName, resourceName) {
  def domain = null
  def wamDomain = 'wam'
  def wamDomainSecret = "${env.WAM_DOMAIN_CRED}"

  if (fileExists("${workspace}/terraform-vmware/${organizationName}/${repoName}/${resourceName}/env.groovy")) {
    echo "Yes, ${workspace}/terraform-vmware/${organizationName}/${repoName}/${resourceName}/env.groovy exists"
    load "${workspace}/terraform-vmware/${organizationName}/${repoName}/${resourceName}/env.groovy"
    domain = sh(returnStdout: true, script: "printenv WAM_DOMAIN")
  } else {
    echo "no, ${workspace}/terraform-vmware/${organizationName}/${repoName}/${resourceName}/env.groovy does not exist"
  }

  if (domain != null) {
    domain = domain.trim()
    if (domain != 'null') {
      echo domain
      wamDomain = domain
    }
  }

  echo wamDomain
  if (wamDomain == 'wamdmz') {
    wamDomainSecret = "${env.WAMDMZ_DOMAIN_CRED}"
  }
  echo wamDomainSecret
  return wamDomainSecret
}

def terraformVsphereCheckPoint(terraActionType, buildNumber, builderTag, organizationName,
                        repoName, resourceName, gitCommit) {
  stage("Should I Approve ${terraActionType}?") {
    checkpoint "terraform ${terraActionType}"
    currentBuild.displayName = "${organizationName}-${repoName}-${resourceName}-${buildNumber}"
    def didTimeout = false
    def didAbort = false
    try {
      timeout(time: 60, unit: 'SECONDS') { // change to a convenient timeout for you
        userInput = input(id: 'Proceed1', message: "Approve Terraform ${terraActionType}?")
      }
    } catch(err) { // timeout reached or input false
      def user = err.getCauses()[0].getUser()
      if('SYSTEM' == user.toString()) { // SYSTEM means timeout.
        didTimeout = true
      } else {
        didAbort = true
        echo "Aborted by: [${user}]"
      }
    }

    if (didTimeout) {
      // do something on timeout
      echo "no input was received before timeout"
      currentBuild.result = 'SUCCESS'
    } else if (didAbort) {
      // do something else
      echo "this was not successful"
      currentBuild.result = 'SUCCESS'
    } else {
      if (terraActionType == 'apply') {
        terraformVsphereApply(buildNumber, builderTag, organizationName, repoName, resourceName, gitCommit)
     } else {
        terraformVsphereDestroy(buildNumber, builderTag, organizationName, repoName, resourceName, gitCommit)
      }
    }
  }
}

def terraformVsphereApply(buildNumber, builderTag, organizationName, repoName, resourceName, gitCommit) {
  echo builderTag
  echo organizationName
  echo repoName
  echo resourceName

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'tf', image: "${builderImage}", ttyEnabled: true, command: 'cat')
  ]) {
    node(POD_LABEL) {
      // Clean workspace before doing anything
      deleteDir()
      try {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.GHE_JENKINS_CRED}",
           usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
           sh """
             git config --global user.email "jenkins@westernasset.com"
             git config --global user.name "Jenkins Agent"
             git config --global http.sslVerify false
             git config --global push.default matching
             git config -l
             git clone https://$USERNAME:$PASSWORD@github.westernasset.com/iac/terraform-vmware.git
             cd ./terraform-vmware
             git reset --hard $gitCommit
           """
        }
        def wamDomainSecret = getDomainSecret(workspace, organizationName, repoName, resourceName)
        currentBuild.displayName = "${organizationName}-${repoName}-${resourceName}-${buildNumber}-apply"
        container('tf') {
          //sh 'set'
          //echo sh(script: 'env|sort', returnStdout: true)

          stage ('Terraform Apply') {
            withCredentials([usernamePassword(credentialsId:"${env.VSPHERE_CRED}", usernameVariable: 'USER', passwordVariable: 'PWD'),
                             usernamePassword(credentialsId: "${wamDomainSecret}", usernameVariable: 'DOMAIN_USERNAME', passwordVariable: 'DOMAIN_PASSWORD'),
                             usernamePassword(credentialsId: "${env.AWS_WAMCO_CRED}", usernameVariable: 'ACCESS_KEY', passwordVariable: 'SECRET_KEY')]) {
                sh """
                  cd ./terraform-vmware/${organizationName}/${repoName}/${resourceName}
                  terraform init -backend-config="access_key=${ACCESS_KEY}" -backend-config="secret_key=${SECRET_KEY}"
                  terraform plan -var vsphere_user=${USER} -var vsphere_password=${PWD} -var domainjoin_admin_user=${DOMAIN_USERNAME} -var domainjoin_admin_password=${DOMAIN_PASSWORD}
                  terraform apply -var vsphere_user=${USER} -var vsphere_password=${PWD} -var domainjoin_admin_user=${DOMAIN_USERNAME} -var domainjoin_admin_password=${DOMAIN_PASSWORD} -auto-approve
                """
            }
    	    }
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
  terraformVsphereCheckPoint('destroy', buildNumber, builderTag, organizationName, repoName, resourceName, gitCommit)
}

def terraformVsphereDestroy(buildNumber, builderTag, organizationName, repoName, resourceName, gitCommit) {

  echo builderTag
  echo organizationName
  echo repoName
  echo resourceName
  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'tf', image: "${builderImage}", ttyEnabled: true, command: 'cat')
  ]) {
    node(POD_LABEL) {
      // Clean workspace before doing anything
      deleteDir()
      try {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.GHE_JENKINS_CRED}",
          usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
           sh """
             git config --global user.email "jenkins@westernasset.com"
             git config --global user.name "Jenkins Agent"
             git config --global http.sslVerify false
             git config --global push.default matching
             git config -l
             git clone https://$USERNAME:$PASSWORD@github.westernasset.com/iac/terraform-vmware.git
             ls -la
             cd ./terraform-vmware
             git reset --hard $gitCommit
           """
        }
        def wamDomainSecret = getDomainSecret(workspace, organizationName, repoName, resourceName)
        currentBuild.displayName = "${organizationName}-${repoName}-${resourceName}-${buildNumber}-destroy"
        container('tf') {
          //sh 'set'
          //echo sh(script: 'env|sort', returnStdout: true)
          stage ('Terraform Destroy') {
            withCredentials([usernamePassword(credentialsId:"${env.VSPHERE_CRED}", usernameVariable: 'USER', passwordVariable: 'PWD'),
                             usernamePassword(credentialsId: "${wamDomainSecret}", usernameVariable: 'DOMAIN_USERNAME', passwordVariable: 'DOMAIN_PASSWORD'),
                             usernamePassword(credentialsId: "${env.AWS_WAMCO_CRED}", usernameVariable: 'ACCESS_KEY', passwordVariable: 'SECRET_KEY')]) {
              sh """
                cd ./terraform-vmware/${organizationName}/${repoName}/${resourceName}
                terraform init -backend-config="access_key=${ACCESS_KEY}" -backend-config="secret_key=${SECRET_KEY}"
                terraform plan -var vsphere_user=${USER} -var vsphere_password=${PWD} -var domainjoin_admin_user=${DOMAIN_USERNAME} -var domainjoin_admin_password=${DOMAIN_PASSWORD}
                terraform destroy -var vsphere_user=${USER} -var vsphere_password=${PWD} -var domainjoin_admin_user=${DOMAIN_USERNAME} -var domainjoin_admin_password=${DOMAIN_PASSWORD} -auto-approve
              """
            }
    	    }
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
    }
  }
}
