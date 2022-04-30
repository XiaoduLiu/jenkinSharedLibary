#!/usr/bin/groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()
  def label = "agent-${UUID.randomUUID().toString()}"

  def musshMap = config.mussh_cmd
  def scripts = config.script_cmd

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
      def branchName
      try {
        stage ('Clone Git Repo') {
          deleteDir()
          checkout scm

          branchName = "${env.BRANCH_NAME}"
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

          privateKeyRoot = "secret/devops/jenkins/ssh-keys/jenkins-agent-to-vm-distribution"
        }

        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${branchName}"
        sh "git reset --hard ${gitCommit}"

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
            if (musshMap!=null) {
              musshMap.each{ key, val ->
                sh """
                  mussh -b -i $workspace/id_rsa -l root -H $workspace/$key -C $workspace/$val -m0
                """
              }
            }
            if (scripts!=null) {
              def sshlist = scripts.split(',')
              sshlist.each{ runScript ->
                sh """
                  chmod +x $workspace/$runScript
                  export key=$workspace/id_rsa
                  $workspace/$runScript
                """
              }
            }
          }

          def gitReleaseTagName = "${appGitRepoName}-${branchName}-${env.BUILD_NUMBER}"
          sh """
            git config --global user.email "jenkins@westernasset.com"
            git config --global user.name "Jenkins Agent"
            git config --global http.sslVerify false
            git config --global push.default matching
            git config -l

            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${gitReleaseTagName}"'
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
          """

        }
      } catch (err) {
        throw err
      }
    }
  }
}
