package com.westernasset.pipeline.util;

def processTableauResource(files, names, secrets, projects, resourceType, deployEnv, tabbedFlag, organizationName, appGitRepoName)  {
  def tableauUrl = "${env.TABLEAU_URL}"
  def site
  def isDeployToProd = true

  if (deployEnv == 'dev' || deployEnv == 'Dev' || deployEnv == 'DEV') {
    tableauUrl = "${env.TABLEAU_URL}/#"
    isDeployToProd = false
  } else if (deployEnv == 'qa' || deployEnv == 'Qa' || deployEnv == 'QA') {
    tableauUrl = "${env.TABLEAU_URL}/#/site/QA"
    site = 'QA'
    isDeployToProd = false
  } else {
    tableauUrl = "${env.TABLEAU_URL}/#"
    isDeployToProd = true
  }

  if (files != 'null') {
    def f = files.split('\n')
    def n = names.split('\n')
    def p = projects.split('\n')
    def s = secrets.split('\n')

    def fdam = f.length
    def i = 0
    while (i < fdam) {
      def file = f[i]
      echo "index=" + i
      def name = n[i]
      def project = p[i]

      def secret = null
      def user = null
      def pass = null

      if (secrets != 'null') {
        if (s[i]!='none' && s[i]!='None' && s[i]!='NONE') {
          secret = s[i]
          def secretRoot = "secret/${organizationName}/${appGitRepoName}/nonprod/${secret}"
          def appRoleName = organizationName + '-' + appGitRepoName + '-nonprod'
          if (isDeployToProd) {
            secretRoot = "secret/${organizationName}/${appGitRepoName}/prod/${secret}"
            appRoleName = organizationName + '-' + appGitRepoName + '-prod'
          }
          //get the app vault auth token
          def appVaultAuthToken = generateVaultAuthToken(appRoleName, isDeployToProd);
          container('tableau') {
            sh """
             cp /opt/db.ctmpl $workspace/db.ctmpl
            """
          }
          container('vault') {
            withEnv(["VAULT_TOKEN=${appVaultAuthToken}", "SECRET_ROOT=${secretRoot}"]) {
              sh """
                consul-template -vault-renew-token=false -once -template $workspace/db.ctmpl:$workspace/db.groovy
              """
              def tempScript = load "$workspace/db.groovy"
              def envMap = tempScript.getSecretMap()
              user = envMap['username']
              pass = envMap['password']
            }
          }
        }
      }

      container('tableau') {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.TABLEAU_CREDENTIAL}",
          usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
          def siteArg = (site != null) ? "-t ${site}" : ""
          def secretArg = (secret != null) ? "--db-username '$user' --db-password '$pass' --save-db-password" : ""
          def secretArgDisplay = (secret != null) ? "--db-username '$user' --db-password 'xxxxxx' --save-db-password" : ""
          def tabbedArg = (resourceType == 'twb' && tabbedFlag) ? "--tabbed" : ""
          def displayCmd ="tabcmd publish ${file} --name ${name} --overwrite --project ${project} $secretArgDisplay --no-certcheck --accepteula $tabbedArg "

          sh """
            tabcmd login -s $tableauUrl -u $USERNAME -p $PASSWORD $siteArg --accepteula --no-certcheck
            echo $displayCmd
            set +x
            tabcmd publish '$file' --name '$name' --overwrite --project '$project' $secretArg --no-certcheck --accepteula $tabbedArg
            set -x
          """
        }
      }
      i = i + 1
    }
  } else {
    if (names != 'null') {
      def nlist = names.split('\n')
      def plist = projects.split('\n')

      def ndam = nlist.length
      def i = 0
      while (i < ndam) {
        echo "index=" + i
        def name = nlist[i]
        def project = plist[i]

        container('tableau') {
          withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.TABLEAU_CREDENTIAL}",
            usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
            def siteArg = (site != null) ? "-t ${site}" : ""
            def displayCmd ="tabcmd delete ${name} --project ${project} --no-certcheck --accepteula"
            sh """
              tabcmd login -s $tableauUrl -u $USERNAME -p $PASSWORD $siteArg --accepteula --no-certcheck
              echo $displayCmd
              set +x
              tabcmd delete '${name}' --project '${project}' --no-certcheck --accepteula
              set -x
            """
          }
        }

        i=i+1
      }
    }
  }
}
