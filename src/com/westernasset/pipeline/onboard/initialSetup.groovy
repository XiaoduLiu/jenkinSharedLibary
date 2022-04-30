package com.westernasset.pipeline.onboard;

import jenkins.model.Jenkins
import hudson.util.Secret
import com.cloudbees.hudson.plugins.folder.*;
import com.cloudbees.hudson.plugins.folder.properties.*;
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider.FolderCredentialsProperty;
import com.cloudbees.plugins.credentials.impl.*;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.*;
import org.jenkinsci.plugins.plaincredentials.impl.*;
import groovy.text.*;

def createGitRepo(needRepoCreate, appTeam, repoName) {
  if (needRepoCreate == 'true') {
    mymap = [:]
    mymap.ORG = appTeam
    mymap.REPO = repoName
    print mymap
    def fileContents = readFile("${workspace}/terraform/state.ctmpl")
    def result = fileContents

    mymap.each { k, v ->
      def keyStr = "${k}"
      def keyString = '\\$\\{' + keyStr + '\\}'
      result = result.replaceAll(keyString, v)
    }
    writeFile file: "${workspace}/terraform/state.tf", text: result
    try {
      container("tf") {
        sh """
          ls -la
          ls -la $workspace/terraform
          echo $workspace
          cat $workspace/terraform/state.tf
        """​​
      }
    } catch (ax) {
      echo ax.getMessage()
    }
    container('tf') {
      withCredentials([string(credentialsId: "${env.GHE_JENKINS_GIT_TOKEN}", variable: 'GIT_TOKEN')]) {
        sh """
          echo "Using Terraform version"
          terraform -version
          if [ -d "$workspace/terraform" ]; then
            ### Take action if "$workspace/terraform" exists ###
            echo "Running Git Repo Creation..."
            cd $workspace/terraform
            terraform init -backend-config="profile=nonprod"
            terraform plan -var="token=${GIT_TOKEN}" -var="project_name=${repoName}" -var="organization=${appTeam}"
            terraform apply -var="token=${GIT_TOKEN}" -var="project_name=${repoName}" -var="organization=${appTeam}" -auto-approve
          else
            ###  Control will jump here if "$workspace/terraform" does NOT exist ###
            echo "Error: $workspace/terraform not found. Can not continue."
            exit 1
          fi
        """
      }
    }
  }
}

def populateProjectTemplate(appTeam, repoName, appTemplateType, projectType) {
  def appGitScm = "git@github.westernasset.com:${appTeam}/${repoName}.git"

  def appGitRepo = "${repoName}-app"
  if (appTemplateType != 'Other' && appTemplateType.contains(projectType)) {
    try {
      container('ct') {
        def TEMPLATE_GIT_ORG='devops'
        def submoduleGitRepoName = 'wam-templates-submodules'
        def appTemplateGitScm = "git@github.westernasset.com:devops/wam-templates.git"
        def cookiecutterRoot = "\\{\\{cookiecutter.project_name\\}\\}"

        def submoduleDirName = 'base'
        if (appTemplateType.contains('Service')) {
          submoduleDirName = 'service'
        } else if (appTemplateType.contains('Batch')) {
          submoduleDirName = 'batch'
        } else if (appTemplateType.contains('helm')) {
          submoduleDirName = 'helm'
        }

        withCredentials([string(credentialsId: "${env.GHE_JENKINS_GIT_TOKEN}", variable: 'GIT_TOKEN')]){
          sh """
            mkdir $appTeam
            cd $appTeam
            id

            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git clone $appGitScm'
            mv $repoName $appGitRepo

            # Cookiecutter
            echo "default_context:" > config.yaml
            echo "    project_name: ${repoName}" >> config.yaml
            echo "    organization: ${appTeam}" >> config.yaml
            echo "    group: DEVOPS"  >> config.yaml
            echo "    waplatform_secret: no" >> config.yaml
            echo "    matillion_secret: no"  >> config.yaml
            cat config.yaml

            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git clone $appTemplateGitScm'
            cd ./wam-templates
            git submodule init
            git submodule update
            pwd
            cd ./$appTemplateType/$cookiecutterRoot
            pwd
            cp -f ./$submoduleGitRepoName/base/* .
            echo $submoduleDirName
            cp -Rf ./$submoduleGitRepoName/$submoduleDirName/* .
            pwd
            ls -la
            rm -rf ./$submoduleGitRepoName
            ls -la
            cd ../../..
            ls -la

            cookiecutter  --config-file config.yaml ./wam-templates/$appTemplateType --no-input
            if [ ! -d $workspace/$repoName ]; then
              echo "Template creation failed"
            fi
            pwd
            ls -la
            cd $repoName
            rm -Rf .git
            rm .gitignore
            cd ..

            cp -a $repoName/. $appGitRepo/
            cd $appGitRepo

            git status
            git config --global user.email "jenkins@westernasset.com"
            git config --global user.name "Jenkins Agent"
            git config --global http.sslVerify false
            git config --global push.default matching
            git config -l
            git add .
            git commit -m "Project Generated with Jenkins" --allow-empty
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -u origin master --force'
            cd ..
            ls -la
            cd ..
            rm -Rf $appTeam
            ls -la
          """
        }
      }
  } catch(ex) {
    try {
      sh """
        rm -Rf $workspace/$appTeam
      """
    } catch(e) {
      println e.getMessage()
    }
    println ex.getMessage()
  }
  }
}

def createPolicy(appTeam, appName, appPath) {
  stage ('Create Policies') {
    //create the nonprod app & Jenkins policies
    sh """
      echo 'path "secret/ssl/*" { capabilities = ["read", "list"] }' > ./policy/nonprod/"${appName}"-nonprod.hcl
      echo 'path "secret/devops/jenkins-agent/nonprod/*" { capabilities = ["read", "list"] }' >> ./policy/nonprod/"${appName}"-nonprod.hcl
      echo 'path "secret/'"${appPath}"'/nonprod/*" { capabilities = ["read", "list"] }' >> ./policy/nonprod/"${appName}"-nonprod.hcl
      more policy/nonprod/${appName}-nonprod.hcl
    """
    //create jenkins-nonprod-role.hcl if it does not exist
    sh """
      if [ -f "./policy/jenkins-nonprod-role.hcl" ]
      then
        echo "./policy/jenkins-nonprod-role.hcl exists!"
      else
        echo "./policy/jenkins-nonprod-role.hcl does not exist, so create one!"
        echo "#jenkins-nonprod-role policies" > ./policy/jenkins-nonprod-role.hcl
      fi
      ls -la ./policy
    """
    try {
      sh(returnStdout: true, script: "grep ${appName} ./policy/jenkins-nonprod-role.hcl")
    } catch (err) {
        echo "not found"
        sh """
          echo 'not found string ------------'
          echo 'path "auth/approle/role/'"${appName}"'-nonprod/secret-id" { capabilities = ["read", "create", "update"] }\n' >> ./policy/jenkins-nonprod-role.hcl
          more ./policy/jenkins-nonprod-role.hcl
        """
    }

    //create the prod app & Jenkins policies
    sh """
      echo 'path "secret/ssl/*" { capabilities = ["read", "list"] }' > ./policy/prod/"${appName}"-prod.hcl
      echo 'path "secret/devops/jenkins-agent/prod/*" { capabilities = ["read", "list"] }' >> ./policy/prod/"${appName}"-prod.hcl
      echo 'path "secret/'"${appPath}"'/prod/*" { capabilities = ["read", "list"] }' >> ./policy/prod/"${appName}"-prod.hcl
      echo 'path "secret/'"${appPath}"'/prod" { capabilities = ["read", "list"] }' >> ./policy/prod/"${appName}"-prod.hcl
      more policy/prod/${appName}-prod.hcl
      more policy/prod/${appName}-prod.hcl
    """
    //create jenkins-prod-role.hcl if it does not exist
    sh """
      if [ -f "./policy/jenkins-prod-role.hcl" ]
      then
        echo "./policy/jenkins-prod-role.hcl exists!"
      else
        echo "./policy/jenkins-prod-role.hcl does not exist, so create one!"
        echo "#jenkins-prod-role policies" > ./policy/jenkins-prod-role.hcl
      fi
      ls -la ./policy
    """
    try {
      sh(returnStdout: true, script: "grep ${appName} ./policy/jenkins-prod-role.hcl")
    } catch (err) {
      echo "not found"
      sh """
        echo 'not found string ------------'
        echo 'path "auth/approle/role/'"${appName}"'-prod/secret-id" { capabilities = ["read", "create", "update"] }\n' >> ./policy/jenkins-prod-role.hcl
        more ./policy/jenkins-prod-role.hcl
      """
    }

    //create the developer application policies
    sh """
      echo 'path "secret/ssl/*" { capabilities = ["read", "list"] }' > ./policy/app/${appName}-nonprod-app.hcl
      echo 'path "secret/'"${appPath}"'/nonprod/*" { capabilities = ["create", "read", "update", "delete", "list"] }' >> ./policy/app/${appName}-nonprod-app.hcl
      more policy/app/${appName}-nonprod-app.hcl
    """

    //create the developer team policy if it does not exist
    sh """
      if [ -f "./policy/appteam/${appTeam}-nonprod-app.hcl" ]
      then
        echo "./policy/appteam/${appTeam}-nonprod-app.hcl exists!"
      else
        echo "./policy/appteam/${appTeam}-nonprod-app.hcl does not exist, so create one!"
        echo "#${appTeam}-nonprod-app policies" > ./policy/appteam/${appTeam}-nonprod-app.hcl
        echo 'path "secret/*" { capabilities = ["list"] }' >> ./policy/appteam/${appTeam}-nonprod-app.hcl
        echo 'path "secret/ssl/*" { capabilities = ["read", "list"] }' >> ./policy/appteam/${appTeam}-nonprod-app.hcl
      fi
      ls -la ./policy/appteam
    """
    try {
      def appPathCorrect = "${appPath}/"
      sh(returnStdout: true, script: "grep ${appPathCorrect} ./policy/appteam/${appTeam}-nonprod-app.hcl")
    } catch (err) {
      echo "not found"
      sh """
        echo 'not found string ------------'
        echo 'path "secret/'"${appPath}"'/nonprod/*" { capabilities = ["create", "read", "update", "delete", "list"] } \n' >> ./policy/appteam/${appTeam}-nonprod-app.hcl
        more ./policy/appteam/${appTeam}-nonprod-app.hcl
      """
    }
    //add the prod path to operations-super policy
    try {
      def appPathCorrect = "${appPath}/"
      sh(returnStdout: true, script: "grep ${appPathCorrect} ./policy/operations-super.hcl")
    } catch (err) {
      echo "not found"
      sh """
        echo 'not found string ------------'
        echo 'path "secret/'"${appPath}"'/prod/*" { capabilities = ["create", "read", "update", "delete", "list"] } \n' >> ./policy/operations-super.hcl
        echo 'path "secret/'"${appPath}"'/prod" { capabilities = ["create", "read", "update", "delete", "list"] } \n' >> ./policy/operations-super.hcl
        more ./policy/operations-super.hcl
      """
    }
    container('vault') {
      //populate the policy for app in vault
      withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
        withCredentials([string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER}", variable: 'VAULT_TOKEN')]) {
          sh """
            vault policy write ${appName}-nonprod ./policy/nonprod/${appName}-nonprod.hcl
            vault policy write ${appName}-prod ./policy/prod/${appName}-prod.hcl
            vault policy write jenkins-nonprod-role ./policy/jenkins-nonprod-role.hcl
            vault policy write jenkins-prod-role ./policy/jenkins-prod-role.hcl
            vault policy write ${appName}-nonprod-app ./policy/app/${appName}-nonprod-app.hcl
            vault policy write ${appTeam}-nonprod-app ./policy/appteam/${appTeam}-nonprod-app.hcl
          """
        }
      }
    }
  }
}

def Map createAppRoles(appName, vaultTokenTTL) {
  def returnMap = [:]
  stage ('Create App-Roles') {
    //create the app role for nonprod & prod
    container('vault') {
      withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
        withCredentials([string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER}", variable: 'VAULT_TOKEN')]) {
          sh """
            vault write auth/approle/role/${appName}-nonprod secret_id_ttl=${vaultTokenTTL}m token_ttl=${vaultTokenTTL}m token_num_uses=1000 token_max_tll=${vaultTokenTTL}m policies="${appName}-nonprod"
            vault write auth/approle/role/${appName}-prod secret_id_ttl=${vaultTokenTTL}m token_ttl=${vaultTokenTTL}m token_num_uses=1000 token_max_tll=${vaultTokenTTL}m policies="${appName}-prod"
          """

          def nonProdRoleId = sh(returnStdout: true, script: "vault read -field=role_id auth/approle/role/${appName}-nonprod/role-id").trim()
          echo "nonProdRoleId -> ${nonProdRoleId}"
          def prodRoleId = sh(returnStdout: true, script: "vault read -field=role_id auth/approle/role/${appName}-prod/role-id").trim()
          echo "prodRoleId -> ${prodRoleId}"

          returnMap['nonProdRoleId'] = "${nonProdRoleId}"
          returnMap['prodRoleId'] = "${prodRoleId}"
        }
      }
    }
  }
  return returnMap
}

def updateGroupPolicy(groupType, appTeam) {
  stage ('Update Group Policy') {
    //create the app role for nonprod & prod
    container('vault') {
      def groupName = "ghe-${groupType}-${appTeam}"
      withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
        withCredentials([string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER}", variable: 'VAULT_TOKEN')]) {
          sh """
            vault write auth/ldap/groups/${groupName} policies=${appTeam}-nonprod-app
          """
        }
      }
    }
  }
}

def gitCommit(appName) {
  stage ('Commit') {
    sh "echo 'deploying to server ...'"
    try {
      withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.JENKINS_ACCESS_TO_GIT}",
        usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh """
          ls -la
          git config --global user.email "jenkins@westernasset.com"
          git config --global user.name "Jenkins Agent"
          git config --global http.sslVerify false
          git config --global push.default matching
          git config -l
          git branch
          git add .
          git commit -m "${appName}"
          git push https://$USERNAME:$PASSWORD@github.westernasset.com/devops/operational-jobs.git --all
        """
      }
    } catch(e) {
      echo e.getMessage()
    }
  }
}

def vmSshKeys(needVmDeploy, appTeam, repoName, appName) {
  stage ('VM SSH Keys') {
    if (needVmDeploy) {
      //non-prod key setup
      try {
        container('vault') {
          withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
            withCredentials([string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER}", variable: 'VAULT_TOKEN')]) {
              sh """
                vault list secret/${appTeam}/${repoName}/nonprod/ssh-keys
              """
            }
          }
        }
      } catch(e) {
        echo e.getMessage()
        sh "echo 'Need to create the VM SSH keys ...'"
        try {
          sh """
            ssh-keygen -q -t rsa -f ${appName}-nonprod -N ''
            pwd
            ls -la
          """
          container('vault') {
            withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
              withCredentials([string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER}", variable: 'VAULT_TOKEN')]) {
                sh """
                  vault write secret/${appTeam}/${repoName}/nonprod/ssh-keys/id_rsa private_key=@${appName}-nonprod
                  vault write secret/${appTeam}/${repoName}/nonprod/ssh-keys/id_rsa.pub public_key=@${appName}-nonprod.pub
                """
              }
            }
          }
        } catch(ee) {
          echo ee.getMessage()
        }
      }
      //prod key setup
      try {
        container('vault') {
          withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
            withCredentials([string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER}", variable: 'VAULT_TOKEN')]) {
              sh """
                vault list secret/${appTeam}/${repoName}/prod/ssh-keys
                echo 'find the keys'
              """
            }
          }
        }
      } catch(e) {
        echo e.getMessage()
        sh "echo 'Need to create the VM SSH keys ...'"
        try {
          sh """
            ssh-keygen -q -t rsa -f ${appName}-prod -N ''
            pwd
            ls -la
          """
          container('vault') {
            withEnv(["VAULT_ADDR=${env.VAULT_ADDR}"]) {
              withCredentials([string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER}", variable: 'VAULT_TOKEN')]) {
                sh """
                  vault write secret/${appTeam}/${repoName}/prod/ssh-keys/id_rsa private_key=@${appName}-prod
                  vault write secret/${appTeam}/${repoName}/prod/ssh-keys/id_rsa.pub public_key=@${appName}-prod.pub
                """
              }
            }
          }
        } catch(ee) {
          echo ee.getMessage()
        }
      }
    } else {
      sh "echo 'No need to create the VM SSH keys ...'"
    }
  }
}

def defaultNonProdSetup(projectType, orgName, repoName, templateMap, roleIdMap, lockableLabel, nonProdRoot, prodRoot) {
    println 'Template map after receiving from upstream (non-prod setup):'
    println templateMap
    println projectType

    projectsWithNonProdReleaseJob = ['mavenDockerService', 'dockerService', 'dockerMultiService']
    projectsWithSiteDeployJob = ['mavenDockerService', 'mavenDockerBatch', 'mavenLib', 'mavenSSHDeploy']
    lockLabel = (lockableLabel == null || lockableLabel.isEmpty()) ? repoName : lockableLabel

    println projectType
    println projectsWithNonProdReleaseJob
    println projectsWithSiteDeployJob

    println 'lock label'
    println lockLabel

    stage('Team non-prod folder & job setup') {
        try {
            if (projectType in projectsWithSiteDeployJob && projectType in projectsWithNonProdReleaseJob) {
                addFolder("$nonProdRoot", orgName, '', [])
                addFolder("$nonProdRoot", orgName, repoName, [
                        "opsReleaseJob=$prodRoot/$orgName/$repoName/ops-release",
                        "siteDeployJob=$nonProdRoot/$orgName/$repoName/site-deploy",
                        "nonProdReleaseDeployJob=$nonProdRoot/$orgName/$repoName/nonprod-release-deploy",
                        "lockLabel=$lockLabel"
                ])
                addMultibranchJobXml("$nonProdRoot", orgName, repoName, 'build-snapshot')
                addTemplateInstance("$nonProdRoot", orgName, repoName, 'site-deploy', templateMap['siteDeployTemplate'])
                addTemplateInstance("$nonProdRoot", orgName, repoName, 'nonprod-release-deploy', templateMap['nonProdReleaseTemplate'])
            } else if (projectType in projectsWithNonProdReleaseJob) {
                addFolder("$nonProdRoot", orgName, '', [])
                addFolder("$nonProdRoot", orgName, repoName, ["opsReleaseJob=$prodRoot/$orgName/$repoName/ops-release",
                                                              "nonProdReleaseDeployJob=$nonProdRoot/$orgName/$repoName/nonprod-release-deploy",
                                                                "lockLabel=$lockLabel"])
                addMultibranchJobXml("$nonProdRoot", orgName, repoName, 'build-snapshot')
                addTemplateInstance("$nonProdRoot", orgName, repoName, 'nonprod-release-deploy', templateMap['nonProdReleaseTemplate'])
            } else if (projectType in projectsWithSiteDeployJob) {
                addFolder("$nonProdRoot", orgName, '', [])
                if (projectType.equalsIgnoreCase('javaLib')) {
                    addFolder("$nonProdRoot", orgName, repoName, ["siteDeployJob=$nonProdRoot/$orgName/$repoName/site-deploy", "lockLabel=$lockLabel"])

                    addMultibranchJobXml("$nonProdRoot", orgName, repoName, 'build-snapshot')
                    addTemplateInstance("$nonProdRoot", orgName, repoName, 'site-deploy', templateMap['siteDeployTemplate'])
                } else {
                    addFolder("$nonProdRoot", orgName, repoName, ["opsReleaseJob=$prodRoot/$orgName/$repoName/ops-release",
                                                                  "siteDeployJob=$nonProdRoot/$orgName/$repoName/site-deploy",
                                                                  "lockLabel=$lockLabel"])
                    addMultibranchJobXml("$nonProdRoot", orgName, repoName, 'build-snapshot')
                    addTemplateInstance("$nonProdRoot", orgName, repoName, 'site-deploy', templateMap['siteDeployTemplate'])
                }
            } else if (projectType.equalsIgnoreCase('liquibase')) {
                addFolder("$nonProdRoot", orgName, '', [])
                addFolder("$nonProdRoot", orgName, repoName, ["opsReleaseJob=$prodRoot/$orgName/$repoName/ops-release",
                                                              "snapshotLiquibaseRollback=$nonProdRoot/$orgName/$repoName/rollback",
                                                              "lockLabel=$lockLabel"])

                addMultibranchJobXml("$nonProdRoot", orgName, repoName, 'build-snapshot')
                addTemplateInstance("$nonProdRoot", orgName, repoName, 'rollback', templateMap['nonProdLiquibaseRollbackTemplate'])
            } else {
                addFolder("$nonProdRoot", orgName, '', [])
                addFolder("$nonProdRoot", orgName, repoName, ["opsReleaseJob=$prodRoot/$orgName/$repoName/ops-release",
                                                              "lockLabel=$lockLabel"])
                addMultibranchJobXml("$nonProdRoot", orgName, repoName, 'build-snapshot')
            }
        } catch (e) {
            println e
            println e.getMessage()
        }
    }

    stage('Add non-prod credentials to team folder') {
        def nonProdFolder = findTeamNonProdFolder(repoName, nonProdRoot)
        def nonProdSecretId = generateNonProdSecret(roleIdMap)
        addCredentialsToFolder(nonProdFolder, nonProdSecretId, 'nonprod-role-id')
    }

    stage('Add lockable resource') {
        createLockableResource(repoName, lockableLabel)
    }
}

def defaultProdSetup(projectType, orgName, repoName, templateMap, roleIdMap, prodRoot) {
    println 'Template map after receiving from upstream (prod setup):'
    println templateMap

    stage('Prod folder & job setup') {
        try {
            if (!projectType.equalsIgnoreCase('javaLib')) {
                if (projectType.equalsIgnoreCase('liquibase')) {
                    addFolder("$prodRoot", orgName, '', [])
                    addFolder("$prodRoot", orgName, repoName, ["opsLiquibaseRollback=$prodRoot/$orgName/$repoName/ops-rollback"])
                    addTemplateInstance("$prodRoot", orgName, repoName, 'ops-release', templateMap['prodOpsReleaseTemplate'])
                    addTemplateInstance("$prodRoot", orgName, repoName, 'ops-rollback', templateMap['prodLiquibaseRollbackTemplate'])
                } else if (projectType.equalsIgnoreCase("dockerImages")) {
                    addFolder("$prodRoot", orgName, '', [])
                    addFolder("$prodRoot", orgName, repoName, [])
                    addTemplateInstance("$prodRoot", orgName, repoName, 'ops-release', templateMap['prodOpsReleaseTemplate'])
                } else {
                    addFolder("$prodRoot", orgName, '', [])
                    addFolder("$prodRoot", orgName, repoName, [])
                    addTemplateInstance("$prodRoot", orgName, repoName, 'ops-release', templateMap['prodOpsReleaseTemplate'])
                }
                stage('Add prod credentials to team folder') {
                    println "ADD prod credentials to team folder"
                    println repoName
                    println prodRoot

                    def prodFolder = findTeamProdFolder(repoName, prodRoot)
                    println prodFolder

                    def prodSecretId = generateProdSecret(roleIdMap)
                    println 'prodSecretId = ' + prodSecretId

                    addCredentialsToFolder(prodFolder, prodSecretId, 'prod-role-id')
                }
            }
        } catch (e) {
            println e
            println e.getMessage()
        }
    }
}

def getPluginVersionMap() {
    def pluginVersionMap = [:]
    def plugins = Jenkins.instance.getPluginManager().getPlugins()
    for (int i = 0; i < plugins.size(); i++) {
        def curPlugin = plugins[i]
        pluginVersionMap[curPlugin.getShortName()] = curPlugin.getVersion()
    }
    return pluginVersionMap
}

def addFolder(rootName, orgName, repoName, folderEnvVars) {
    def pluginVersionMap = getPluginVersionMap()
    jobDsl targets: "jobdsl/addFolder.groovy",
            additionalParameters: [root: "$rootName", gitOrgName: "$orgName", gitRepoName: "$repoName", folderEnvVars: folderEnvVars, pluginVersionMap: pluginVersionMap],
            ignoreExisting: true
}

def addMultibranchJobXml(rootName, orgName, repoName, projectName) {
    def parentFolder = Jenkins.instance.getItemByFullName("$rootName/$orgName/$repoName")
    println 'workspace: '
    println "${env.WORKSPACE}"

    sh """
      ls -la $env.WORKSPACE
      ls -la $env.WORKSPACE/jobxml
      cat $env.WORKSPACE/jobxml/multibranchProject.xml
    """

    def fileContents = readFile "${env.WORKSPACE}/jobxml/multibranchProject.xml"
    println 'loaded job build-snapshot xml file'
    println fileContents

    def pluginVersionMap = getPluginVersionMap()

    Map model = [
            gitRepoName: "$gitRepoName",
            gitOrgName: "$gitOrgName",
            credentialsId: "${env.JENKINS_ACCESS_TO_GIT}",
            BranchSourceList: '$BranchSourceList',
            TrustPermission: '$TrustPermission',
            workflowMultibranchVersion: pluginVersionMap['workflow-multibranch'],
            cloudbeesFolderPlusVersion: pluginVersionMap['cloudbees-folder-plus'],
            hashicorpVaultPluginVersion: pluginVersionMap['hashicorp-vault-plugin'],
            pipelineModelDefinitionVersion: pluginVersionMap['pipeline-model-definition'],
            dockerCommonsVersion: pluginVersionMap['docker-commons'],
            branchApiVersion: pluginVersionMap['branch-api'],
            cloudbeesFolderVersion: pluginVersionMap['cloudbees-folder'],
            githubBranchSourceVersion: pluginVersionMap['github-branch-source'],
            scmApiVersion: pluginVersionMap['scm-api'],
            gitVersion: pluginVersionMap['git']
    ]

    def result = new SimpleTemplateEngine().createTemplate(fileContents)
            .make(model).toString()

    println 'job xml templating results'
    println result

    parentFolder.createProjectFromXML("$projectName", new ByteArrayInputStream(result.getBytes()))

    def multibranchJob = Jenkins.instance.getItemByFullName("$rootName/$orgName/$repoName/$projectName")
    multibranchJob.save()
}

def addTemplateInstance(rootName, orgName, repoName, projectName, templateName) {
    def pluginVersionMap = getPluginVersionMap()
    jobDsl targets: "jobdsl/addTemplateInstance.groovy",
            additionalParameters: [root: "$rootName", gitOrgName: "$orgName", gitRepoName: "$repoName", projectName: "$projectName", templateName: "$templateName", pluginVersionMap: pluginVersionMap],
            ignoreExisting: true
}

def addCredentialsToFolder(folder, roleIdSecret, roleId) {
    println folder
    println roleIdSecret
    println roleId
    AbstractFolder<?> teamFolder = AbstractFolder.class.cast(folder)
    println teamFolder
    FolderCredentialsProperty property = teamFolder.getProperties().get(FolderCredentialsProperty.class)
    println property
    Credentials roleIdCredentials = new StringCredentialsImpl(CredentialsScope.GLOBAL, roleId, roleId, roleIdSecret)

    if (property) {
        println 'working here where property is not null'
        property.getStore().addCredentials(Domain.global(), roleIdCredentials)
    } else {
        println 'working here where property is null'
        property = new FolderCredentialsProperty([roleIdCredentials])
        teamFolder.addProperty(property)
    }
}

def createLockableResource(gitRepoName, label) {
    println  'create lockable resource'
    println  'git repo name:'
    println   gitRepoName
    println  'label:'
    println   label
    def lockableResourceManager = Jenkins.instance.getPluginManager().uberClassLoader.loadClass("org.jenkins.plugins.lockableresources.LockableResourcesManager")
    if (label == null || label.isEmpty()) {
        lockableResourceManager.get().createResourceWithLabel(gitRepoName, gitRepoName)
    } else {
        lockableResourceManager.get().createResourceWithLabel(gitRepoName, label)
    }
}

def generateNonProdSecret(roleIdMap) {
    return Secret.fromString(roleIdMap['nonProdRoleId'])
}

def generateProdSecret(roleIdMap) {
    return Secret.fromString(roleIdMap['prodRoleId'])
}

def findTeamNonProdFolder(repoName, nonProdRoot) {
    for (folder in Jenkins.instance.getAllItems(Folder.class)) {
        if (folder.name.equalsIgnoreCase(repoName) && folder.getParent().getFullName().contains(nonProdRoot)) {
            println 'found team non-prod folder:'
            def parent = folder.getParent().getFullName()
            def directory = folder.name
            println "$parent/$directory"
            return folder
        }
    }
}

def findTeamProdFolder(repoName, prodRoot) {
    for (folder in Jenkins.instance.getAllItems(Folder.class)) {
        if (folder.name.equalsIgnoreCase(repoName) && folder.getParent().getFullName().contains(prodRoot)) {
            println 'found team prod folder:'
            def parent = folder.getParent().getFullName()
            println 'parent = ' + parent
            def directory = folder.name
            println 'directory = ' + directory
            println "$parent/$directory"
            return folder
        }
    }
}
