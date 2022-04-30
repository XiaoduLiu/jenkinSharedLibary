#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()
  def label = "agent-${UUID.randomUUID().toString()}"

  podTemplate(
  label: label,
  cloud: 'pas-development',
  serviceAccount: 'jenkins',
  namespace: 'devops-jenkins',
  containers: [
    containerTemplate(name: 'vault', image: "${env.TOOL_VAULT}", ttyEnabled: true),
    containerTemplate(name: 'mussh', image: "${env.TOOL_MUSSH}", ttyEnabled: true)
  ],
  volumes: [
    persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
  ]) {
    node(label) {
      def organizationName
      def appGitRepoName
      def gitScm
      def privateKeyRoot
      try {
        stage ('Clone Git Repo') {
          deleteDir()
          checkout scm

          def branchName = "${env.BRANCH_NAME}"
          echo branchName

          gitCommit=sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
          echo gitCommit

          String gitRemoteURL = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
          echo gitRemoteURL

          gitScm = "git@github.westernasset.com:" + gitRemoteURL.drop(32)
          echo gitScm

          String shortName = gitRemoteURL.drop(32).reverse().drop(4).reverse()
          echo shortName

          def names = shortName.split('/')

          echo names[0]
          echo names[1]

          organizationName = names[0]
          appGitRepoName = names[1]

          //echo sh(script: 'env|sort', returnStdout: true)
          currentBuild.displayName  = "RUN-${env.BUILD_NUMBER}"

          privateKeyRoot = "secret/${organizationName}/${appGitRepoName}/ssh-keys/id_rsa"
        }
        stage ('Run Audit Script') {
          container('vault') {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.SUPER_TK}",
            usernameVariable: 'VAULT_ADDR', passwordVariable: 'VAULT_TOKEN']]) {
              withEnv(["SCRIPT_SERVER_PRIVATE_KEY_ROOT=${privateKeyRoot}"]) {
                sh """
                  consul-template -vault-renew-token=false -once -template /home/jenkins/.ssh/id_rsa_scriptserver.ctmpl:$workspace/id_rsa
                  chmod 400 $workspace/id_rsa
                """
              }
            }
          }
          container('mussh') {
                sh """
                   mussh -b -i $workspace/id_rsa -l root -H $workspace/resources/hosts -C $workspace/audit_system_files_local.sh -m0
                """
          }
        }
      } catch (err) {
        throw err
      }
    }
  }
}
