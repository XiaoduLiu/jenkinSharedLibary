package com.westernasset.pipeline.builds

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.build.*


def build(config, isProduction) {
    def repo
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def jnlp = new Jnlp()
    def vault = new Vault()
    def tableau = new Tableau()
    def git = new Git()
    def ssh = new Ssh()

    BuilderImage buildImage = BuilderImage.fromTag(env, config.builderTag)

    def stageName = (isProduction)?'Prod Deployment':'Non Prod Deployment'

    if (isProduction) {
      pod.node(
        cloud: 'sc-production',
        containers: [
          jnlp.containerTemplate(),
          tableau.containerTemplate(buildImage),
          vault.containerTemplate()],
        volumes: [ ssh.keysVolume() ]
      ) {
        stage('Clone') {
          repo = new GitRepository(
              config.appGitRepoName,
              config.organizationName,
              config.gitScm,
              config.gitCommit,
              config.gitBranchName,
              null)
          gitScm.checkout(repo, 'ghe-jenkins');
          println repo
        }
        stage(stageName) {
          if (!config.datasourceResources.equalsIgnoreCase('null')) {
              println repo
              def ds1 = getObjectFromString(config.datasourceResources)
              def ds2 = getObjectFromString(config.datasourceSettings)
              processingTableauResources(repo, ds1, ds2, isProduction)
          }
          if (!config.workbookResources.equalsIgnoreCase('null')) {
              println repo
              def ds3 = getObjectFromString(config.workbookResources)
              def ds4 = getObjectFromString(config.workbookSettings)
              processingTableauResources(repo, ds3, ds4, isProduction)
          }
        }

        def gitReleaseTag = "${repo.safeName}-${config.releaseVersion}"
        git.useJenkinsUser()
        sh "git tag -a $gitReleaseTag -m \"Release for ${config.releaseVersion}\""
        sh "git push origin $gitReleaseTag"
      }
    } else {
      pod.node(
        containers: [
            jnlp.containerTemplate(),
            tableau.containerTemplate(buildImage),
            vault.containerTemplate()]
      ) {
        stage('Clone') {
            repo = gitScm.checkout()
        }
        stage(stageName) {
            if (config.datasourceResources != null) {
                processingTableauResources(repo, config.datasourceResources, config.datasourceSettings, isProduction)
            }
            if (config.workbookResources != null) {
                processingTableauResources(repo, config.workbookResources, config.workbookSettings, isProduction)
            }
        }
      }
    }
    return [repo]
}

def getObjectFromString(str) {
  print str
  def p = readJSON text: str
  print p
  print p.get('prod')
  print p.get('nonprod')
  return p
}

def getDBUserPassword(secretFile, filename) {
    if (secretFile!=null) {
      def tempScript = load "$workspace/$filename"
      def envMap = tempScript.getSecretMap()
      return [
        username:envMap['username'],
        password:envMap['password']
      ]
    } else {
      return [:]
    }
}

def deployTableau(isProduction, secretFile, tabResource, settingFile) {
    def up = getDBUserPassword(secretFile, 'db')
    def connectionUser = (secretFile != null)? "--connection_username ${up.username}" : " "
    def connectionPassword = (secretFile != null)? "--connection_password ${up.password}" : " "

    def connectionUserForDisplay = (secretFile != null)? "--connection_username xxxxxx" : " "
    def connectionPasswordForDisplay = (secretFile != null)? "--connection_password xxxxxx" : " "

    def settings = (settingFile != null)? "--configuration_file_path ${settingFile}" : " "
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.TABLEAU_CREDENTIAL}", usernameVariable: 'LOGIN_USER', passwordVariable: 'LOGIN_PASSWORD']]) {
        def cmd = "python ${tabResource} --login_username ${LOGIN_USER} --login_password ${LOGIN_PASSWORD} ${connectionUser} ${connectionPassword} ${settings}"
        def cmdDisplay = "python ${tabResource} --login_username ${LOGIN_USER} --login_password ${LOGIN_PASSWORD} ${connectionUserForDisplay} ${connectionPasswordForDisplay} ${settings}"
        println cmdDisplay
        sh """
            set +x
            $cmd
            set -x
        """
    }
}

def processingTableauResources(repo, resources, settings, isProduction) {
    def vault = new Vault()
    def tableau = new Tableau()
    def settingFile = (isProduction)? settings.get('prod'):settings.get('nonprod')
    resources.each {  tabResource, secret ->
        println tabResource
        println secret
        def map = [:]
        def secretFile;
        map.put('db.ctmpl', 'db')
        tableau.container {
            sh "cp /opt/* ."
        }
        vault.container {
            secretFile = (!secret.equalsIgnoreCase('none'))?vault.processTemplatesForTableau(repo, secret, map, isProduction):null
        }
        tableau.container {
            deployTableau(isProduction, secretFile, tabResource, settingFile)
        }
    }
}
