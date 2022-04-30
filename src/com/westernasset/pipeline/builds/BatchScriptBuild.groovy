package com.westernasset.pipeline.builds

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*

def prodBatchBuild(config, environment, imageTagMap, buildNumber, repo) {
  deploy(config, environment, imageTagMap, buildNumber, null, repo)
}

def nonprodBatchBuild(config, environment, imageTagMap, buildNumber, branchName) {
  deploy(config, environment, imageTagMap, buildNumber, branchName, null)
}

def deploy(config, environment, imageTagMap, buildNumber, branchName, repo){
  def ssh = new Ssh()
  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def vault = new Vault()
  def aws = new Aws()
  def kubectl = new Kubectl()
  def slack = new Slack()
  def environmentConfigurations = new EnvironmentConfigurations()
  Conditionals conditionals = new Conditionals()

  def envToken = environment.split(':')
  String deployEnv = envToken[0]
  String account = envToken[1]

  def sshVolume = (deployEnv == 'prod')? ssh.prodKeysVolume():ssh.keysVolume()

  String eksCluster = aws.getEksCluster(account)
  print eksCluster

  pod.awsNode(
    cloud: eksCluster,
    containers: [ kubectl.containerTemplate(), vault.containerTemplate() ],
    volumes: [ sshVolume ]
  ) {

    if (branchName?.trim()) {
      repo=gitScm.checkout()
    } else {
      gitScm.checkout(repo)
    }

    println repo

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
      if (e.appType.toLowerCase() == 'batch') {
        def secrets = e.secrets
        def appBaseDir = e.dockerFile.split('/')[0]
        def batchAppName = repo.organization + '-' + repo.getSafeName() + '-' + appBaseDir + '-' + deployEnv

        if (e.enabled) {
          try {
            conditionals.when(secrets != null && secrets.size() > 0) {
              def processedSecrets
              print "Secret process step"
              vault.container {
                processedSecrets = vault.processTemplates(repo, deployEnv, secrets)
              }
              kubectl.container {
                kubectl.createSecret(repo, appBaseDir, deployEnv, processedSecrets)
              }
            }
            vault.container {
              vault.processTemplate(repo, deployEnv, '/home/jenkins/.ssh/id_rsa_scriptserver.ctmpl', 'id_rsa_scriptserver')
              sh "chmod 400 id_rsa_scriptserver"
            }
            def imageTag = imageTagMap[appBaseDir]
            Map properties = environmentConfigurations.get(deployEnv, repo, imageTag, appBaseDir)
            environmentConfigurations.apply(properties, "${appBaseDir}/kubernetes/*")
            findFiles(glob: "${appBaseDir}/kubernetes/*.sh").each { file ->
              ssh.scp(
                host: "jenkins@${env.SCRIPT_SERVER}",
                src: file.path,
                dest: "/opt/projects/shared/auth/jenkins/${repo.organization}/${repo.name}/${appBaseDir}/${deployEnv}/${file.name}"
              )
            }
            slack.sendSlackMessage(batchAppName, "BATCH", repo.branch, buildNumber, userId, repo.commit, deployEnv, null)
          } catch(execption) {
            print execption
            slack.sendSlackMessage(batchAppName, "BATCH", repo.branch, buildNumber, userId, repo.commit, deployEnv, execption.getMessage())
            error(execption.getMessage())
          }

        }
      }
    }
  }
}

def nonprodBuild(String environmentLong, DockerImage dockerImage, Map secrets = [:]) {
    def ssh = new Ssh()
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def vault = new Vault()
    def kubectl = new Kubectl()
    def environmentConfigurations = new EnvironmentConfigurations()
    Conditionals conditionals = new Conditionals()

    def environment = environmentLong.contains(':')?environmentLong.split(':')[0]:environmentLong
    def cloudCluster = environmentLong.contains(':')?environmentLong.split(':')[1]:'pas-development'

    pod.node(
        cloud: cloudCluster,
        containers: [ kubectl.containerTemplate(), vault.containerTemplate() ],
        volumes: [ ssh.keysVolume() ]
    ) {
        def repo = gitScm.checkout()

        stage('Generate Secrets') {
            conditionals.when(secrets != null && secrets.size() > 0) {
                def processedSecrets

                vault.container {
                    processedSecrets = vault.processTemplates(repo, environment, secrets)
                }

                kubectl.container {
                    kubectl.createSecret(repo, environment, processedSecrets)
                }
            }
        }

        stage("Deploy Batch Scripts") {
            vault.container {
                vault.processTemplate(repo, environment, '/home/jenkins/.ssh/id_rsa_scriptserver.ctmpl', 'id_rsa_scriptserver')
                sh "chmod 400 id_rsa_scriptserver"
            }

            Map properties = environmentConfigurations.get(environment, repo, dockerImage.tag)
            environmentConfigurations.apply(properties, 'kubernetes/*')
            kubectl.deploy('kubernetes/*.y*ml')
            findFiles(glob: 'kubernetes/*.sh').each { file ->
                ssh.scp(
                    host: "jenkins@${env.SCRIPT_SERVER}",
                    src: file.path,
                    dest: "/opt/projects/shared/auth/jenkins/${repo.organization}/${repo.name}/${environment}/${file.name}"
                )
            }
        }
    }
}

def prodBuild(GitRepository repo, String environment, String target, String tag, Map secrets = [:]) {
    def ssh = new Ssh()
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def vault = new Vault()
    def kubectl = new Kubectl()
    def environmentConfigurations = new EnvironmentConfigurations()
    Conditionals conditionals = new Conditionals()

    pod.node(
        cloud: target,
        containers: [ kubectl.containerTemplate(), vault.containerTemplate() ],
        volumes: [ ssh.prodKeysVolume() ]
    ) {
        gitScm.checkout(repo)

        stage('Generate Secrets') {
            conditionals.when(secrets != null && secrets.size() > 0) {
                def processedSecrets

                vault.container {
                    processedSecrets = vault.processTemplates(repo, environment, secrets)
                }

                kubectl.container {
                    kubectl.createSecret(repo, environment, processedSecrets)
                }
            }
        }

        stage("Deploy Batch Scripts") {
            vault.container {
                vault.processTemplate(repo, environment, '/home/jenkins/.ssh/id_rsa_scriptserver.ctmpl', 'id_rsa_scriptserver')
                sh "chmod 400 id_rsa_scriptserver"
            }

            Map properties = environmentConfigurations.get(environment, repo, tag)
            environmentConfigurations.apply(properties, 'kubernetes/*')
            kubectl.deploy('kubernetes/*.y*ml')
            findFiles(glob: 'kubernetes/*.sh').each { file ->
                ssh.scp(
                    host: "jenkins@${env.SCRIPT_SERVER}",
                    src: file.path,
                    dest: "/opt/projects/shared/auth/jenkins/${repo.organization}/${repo.name}/${environment}/${file.name}"
                )
            }
        }
    }
}

return this
