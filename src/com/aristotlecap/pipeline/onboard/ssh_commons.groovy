package com.aristotlecap.pipeline.onboard;

def sshKeysDistribution(org, repo, serverList, appUser, templatesString, secretsString, serverEnv) {

  def commons = new com.aristotlecap.pipeline.Commons()

  def label = "agent-${UUID.randomUUID().toString()}"

  podTemplate(
    label: label,
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true)
    ],
    volumes: [
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
  ]) {
    node(label) {
      // Clean workspace before doing anything
      deleteDir()
      def userId
      def attachments
      try {
        currentBuild.displayName = "${org}-${repo}-${serverList}-${appUser}"

        stage ('Clone') {
          // Clean workspace before doing anything
          deleteDir()
          checkout scm
        }

        wrap([$class: 'BuildUser']) {
          userId = "${BUILD_USER_ID}"
        }
        print userId

        stage('SSH Key Distribution') {
          //find the app public key secret path
          def secretPath = "secret/${org}/${repo}/nonprod/ssh-keys/id_rsa.pub"
          if (serverEnv == 'prod') {
            secretPath = "secret/${org}/${repo}/prod/ssh-keys/id_rsa.pub"
          }
          echo secretPath

          def appVaultAuthToken = getVaultAuthToken();
          echo "application vault auth token -> ${appVaultAuthToken}"

          commons.templateProcessing(templatesString, secretsString, secretPath, 'null', appVaultAuthToken)

          def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
          echo workspace

          sh """
            ls -la
            chown jenkins:jenkins ${workspace}/id_rsa
            chmod 400 ${workspace}/id_rsa
          """

          def privateKey = readFile "${workspace}/id_rsa"
          //echo privateKey

          def publicKey = readFile "${workspace}/app_id_rsa.pub"

          def pubKey = publicKey.drop(7).trim()
          def pubKeyArray = pubKey.split(' ')
          pubKey = pubKeyArray[0].trim()
          echo pubKey

          def servers = serverList.split(",")
          def dem = servers.length
          def i = 0

          while (i < dem) {
            def server = servers[i]
            server = server.trim()

            def userString = sh(returnStdout: true, script: "ssh -i ${workspace}/id_rsa root@${server} 'grep ${appUser}: /etc/passwd'").trim()
            echo userString
            def userStringToken = userString.split(':')
            def uid = userStringToken[2]
            def gid = userStringToken[3]
            def appUserPath = userStringToken[5]
            echo appUserPath

            try {
              def findKeyString = sh(returnStdout: true, script: "ssh -i ${workspace}/id_rsa root@${server} 'grep ${pubKey} ${appUserPath}/.ssh/authorized_keys'").trim()
              echo "find public key = ${findKeyString}"
            } catch(error) {
              echo error.getMessage()
              sh """
                ssh -i ${workspace}/id_rsa root@${server} 'mkdir -p ${appUserPath}/.ssh'
                cat ${workspace}/app_id_rsa.pub | ssh -i ${workspace}/id_rsa root@${server} 'cat >> ${appUserPath}/.ssh/authorized_keys'
                ssh -i ${workspace}/id_rsa root@${server} 'chown ${uid}:${gid} ${appUserPath}/.ssh/authorized_keys'
              """
            }

            i=i+1
            echo "count =" + i
          }

        }

        attachments = [
          [
            text: "@${userId} SSH key to ${serverList} for application ${org}/${repoName} finished successful!",
            fallback: "@${userId} SSH key to ${serverList} for application ${org}/${repoName} finished successful!",
            color: '#3f6b3b'
          ]
        ]
        slackSend(channel: "@${userId}", attachments: attachments)

      } catch (err) {
          def errorMsg = err.getMessage()
          attachments = [
            [
              text: "@${userId} SSH key to ${serverList} for application ${org}/${repoName} failed with error: ${errorMsg}",
              fallback: "@${userId} SSH key to ${serverList} for application ${org}/${repoName} failed with error: ${errorMsg}",
              color: '#ff0000'
            ]
          ]
          slackSend(channel: "@${userId}", attachments: attachments)

          currentBuild.result = 'FAILED'
          echo err.getMessage()
          throw err
      }
    }
  }
}

def getVaultAuthToken() {
  def token
  container('vault') {
    withCredentials([string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER}", variable: 'VAULT_TOKEN'),
                     string(credentialsId: "${env.JENKINS_VAULT_TOKEN_SUPER_ROLE}", variable: 'ROLE_ID')]) {
      def secretId = sh(script: "vault write -field=secret_id -f auth/approle/role/jenkins-super/secret-id", returnStdout: true)
      echo "secretId ->${secretId}"
      token = sh(script: "vault write -field=token auth/approle/login role_id=${ROLE_ID} secret_id=${secretId}", returnStdout: true)
    }
  }
  return token
}
