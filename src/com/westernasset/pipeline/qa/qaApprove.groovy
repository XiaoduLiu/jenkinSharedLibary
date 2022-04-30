package com.westernasset.pipeline.qa;

def approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
            releaseVersion, organizationName, appGitRepoName, prodEnv, drEnv,
            liquibaseChangeLog, liquibaseBuilderTag, projectType, templates, secrets,
            appVersion, dockerfileToTagMapString, baseDisplayTag, postDeploySteps) {

  approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
          releaseVersion, organizationName, appGitRepoName, prodEnv, drEnv,
          liquibaseChangeLog, liquibaseBuilderTag, projectType, templates, secrets,
          appVersion, dockerfileToTagMapString, baseDisplayTag, postDeploySteps, 'null')

}

def approve(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
            releaseVersion, organizationName, appGitRepoName, prodEnv, drEnv,
            liquibaseChangeLog, liquibaseBuilderTag, projectType, templates, secrets,
            appVersion, dockerfileToTagMapString, baseDisplayTag, postDeploySteps, helmChartVersion) {

  def gateutil = new com.westernasset.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(null, false, true, false, "${baseDisplayTag}", 'Approve Release?')
  if (gate.crNumber != null) {
    approveLogic(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
                 releaseVersion, gate.crNumber, organizationName, appGitRepoName, prodEnv,
                 drEnv, liquibaseChangeLog, liquibaseBuilderTag, projectType, templates,
                 secrets, appVersion, dockerfileToTagMapString, baseDisplayTag, postDeploySteps, helmChartVersion)
  }
}

def approveLogic(gitScm, gitBranchName, gitCommit, buildNumber, appDtrRepo,
                 releaseVersion, userInput, organizationName, appGitRepoName, prodEnv,
                 drEnv, liquibaseChangeLog, liquibaseBuilderTag, projectType, templates,
                 secrets, appVersion, dockerfileToTagMapString, baseDisplayTag, postDeploySteps, helmChartVersion) {

  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'docker', image: "${env.TOOL_DOCKER}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh')
  ]) {

    node(POD_LABEL) {
      def commons = new com.westernasset.pipeline.Commons()
      try {
        // Clean workspace before doing anything
        deleteDir()
        git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
        sh "git reset --hard ${gitCommit}"

        stage("QA approve") {
          currentBuild.displayName = "${baseDisplayTag}-${userInput}"

          //sh 'git config -l'
          //echo sh(script: 'env|sort', returnStdout: true)
          sh """
            git config --global user.email "jenkins@westernasset.com"
            git config --global user.name "Jenkins Agent"
            git config --global http.sslVerify false
            git config --global push.default matching
            git config -l
          """
          echo gitScm
          echo gitBranchName
          echo gitCommit
          echo projectType

          def repoNameLower = appGitRepoName.toLowerCase().replace('.', '-')

          // Clean workspace before doing anything
          deleteDir()
          git url: "${gitScm}", credentialsId: 'ghe-jenkins', branch: "${gitBranchName}"
          sh "git reset --hard ${gitCommit}"

          if (projectType == 'liquibase' ||
              projectType == 'dockerService' ||
              projectType == 'dockerBatch' ||
              projectType == 'dockerMultiService') {

            def gitReleaseTagName = "${appGitRepoName}-${appVersion}"
            sh """
              git config --global user.email "jenkins@westernasset.com"
              git config --global user.name "Jenkins Agent"
              git config --global http.sslVerify false
              git config --global push.default matching
              git config -l

              ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git tag -a $gitReleaseTagName -m "Release for ${userInput}" '
              ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $gitReleaseTagName'
            """
          }

          if (projectType == 'mavenDockerService' ||
              projectType == 'mavenDockerMultiService' ||
              projectType == 'dockerService' ||
              projectType == 'dockerMultiService') {

            if (dockerfileToTagMapString == 'null') {

              def image = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${appDtrRepo}:${releaseVersion}"
              println image
              def crTag = "${releaseVersion}-${userInput}"
              println crTag

              println 'need to push this crTag to non prod'
              def approveImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${appDtrRepo}:${crTag}"
              println  approveImage


              def releaseImage1 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${releaseVersion}"
              def releaseImage2 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${crTag}"

              pushImageToProdDtr(image, approveImage)
              pushImageToProdDtr(image, releaseImage1)
              pushImageToProdDtr(image, releaseImage2)

            } else {
              def workspace = sh(returnStdout: true, script: "printenv WORKSPACE").trim()
              def dockerfileToTagMap = commons.getMapFromString(dockerfileToTagMapString)

              dockerfileToTagMap.each { dockerFile, tag ->

                def composedTag = "${gitBranchName}-${tag}-${buildNumber}"

                def image = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${appDtrRepo}:${composedTag}"
                def crTag = "${composedTag}-${userInput}"

                def approveImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${appDtrRepo}:${crTag}"

                def releaseImage1 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${composedTag}"
                def releaseImage2 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${crTag}"

                container('docker') {
                  withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${env.IMAGE_REPO_CREDENTIAL}",
                    usernameVariable: 'USER', passwordVariable: 'PASS']]) {
                    def t1 = "\"" + "http://${env.IMAGE_REPO_URI}/artifactory/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}/${composedTag}" + "\""
                    def t2 = "\"" + "http://${env.IMAGE_REPO_URI}/artifactory/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}/${crTag}" + "\""
                    def t3 = "\"" + "http://${env.IMAGE_REPO_URI}/artifactory/${env.IMAGE_REPO_NONPROD_KEY}/${organizationName}/${repoNameLower}/${crTag}" + "\""
                    try {
                      sh """
                        curl -u$USER:$PASS -X DELETE $t1
                      """
                    } catch(exp) {}
                    try {
                      sh """
                        curl -u$USER:$PASS -X DELETE $t2
                      """
                    } catch(exp) {}
                    try {
                      sh """
                        curl -u$USER:$PASS -X DELETE $t3
                      """
                    } catch(exp) {}
                  }
                }

                commons.removeTag(releaseImage1)
                commons.removeTag(releaseImage2)
                commons.removeTag(approveImage)
                commons.removeTag(image)

                pushImageToProdDtr(image, approveImage)
                pushImageToProdDtr(image, releaseImage1)
                pushImageToProdDtr(image, releaseImage2)
              }
            }
          } else if (projectType == 'mavenDockerBatch' ||
                     projectType == 'dockerBatch') {

            def image = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${appDtrRepo}:${releaseVersion}"
            println image
            def crTag = "${releaseVersion}-${userInput}"
            println crTag

            println 'need to push this crTag to non prod'
            def approveImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${appDtrRepo}:${crTag}"
            println  approveImage

            def branchVersion = commons.findBrachAndVersion(releaseVersion)

            String liveTag1 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${releaseVersion}"
            String liveTag2 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${branchVersion}-latest"
            String liveTag3 = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${organizationName}/${repoNameLower}:${releaseVersion}-${userInput}"

            echo "liveTag1 -> ${liveTag1}"
            echo "liveTag2 -> ${liveTag2}"
            echo "liveTag3 -> ${liveTag3}"

            pushImageToProdDtr(image, approveImage)
            pushImageToProdDtr(image, liveTag1)
            pushImageToProdDtr(image, liveTag2)
            pushImageToProdDtr(image, liveTag3)
          }
        }
      } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
      }
      stage('trigger downstream job') {
        build job: "${env.opsReleaseJob}", wait: false, parameters: [
          [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(buildNumber)],
          [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(releaseVersion)],
          [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(userInput)],
          [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(gitBranchName)],
          [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(gitCommit)],
          [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(gitScm)],
          [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(organizationName)],
          [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(appGitRepoName)],
          [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(appDtrRepo)],
          [$class: 'StringParameterValue', name: 'prodEnv', value: String.valueOf(prodEnv)],
          [$class: 'StringParameterValue', name: 'drEnv', value: String.valueOf(drEnv)],
          [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: String.valueOf(liquibaseChangeLog)],
          [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: String.valueOf(liquibaseBuilderTag)],
          [$class: 'StringParameterValue', name: 'templates', value: String.valueOf(templates)],
          [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
          [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(projectType)],
          [$class: 'StringParameterValue', name: 'postDeploySteps', value: String.valueOf(postDeploySteps)],
          [$class: 'StringParameterValue', name: 'dockerfileToTagMap', value: String.valueOf(dockerfileToTagMapString)],
          [$class: 'StringParameterValue', name: 'helmChartVersion', value: helmChartVersion]
        ]
      }
    }
  }
}

def pushImageToProdDtr(image, approveImage) {
  container('docker') {
    docker.withRegistry("https://${env.IMAGE_REPO_URI}", "${env.IMAGE_REPO_CREDENTIAL}") {
      sh """
        docker pull $image
        docker tag $image $approveImage
        docker push $approveImage
      """
    }
  }
}
