package com.westernasset.pipeline.builds

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*

def snapshotBuild(BuilderImage builderImage, Closure after = null) {
    snapshotBuild(builderImage.image, null, after)
}

def snapshotBuild(BuilderImage buildImage, List<String> archiveJarLocations, Closure after = null) {
    snapshotBuild(buildImage.image, archiveJarLocations,  after)
}

def snapshotBuild(String buildImage, List<String> archiveJarLocations, Closure after = null) {
    def maven = new Maven()
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def ssh = new Ssh()
    def git = new Git()

    pod.node(
        containers: [ maven.containerTemplate(buildImage) ],
        volumes: [ maven.cacheVolume(), ssh.keysVolume() ]
    ) {
        def repo
        def appVersion
        stage('Maven Snapshot') {
            repo = gitScm.checkout()
            def pom = readMavenPom file: 'pom.xml'
            if (!pom.version) {
                error 'Please set project.version in pom.xml'
            }
            appVersion = (pom.version == '${revision}')?pom.properties.revision:pom.version
            currentBuild.displayName = "${repo.branch}-${appVersion}-${env.BUILD_NUMBER}"
            def additionalOpts = getMavenAdditonalOptions(repo)
            maven.container {
                sh """
                  export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
                  mvn clean ${additionalOpts} deploy
                """
                if (archiveJarLocations != null) {
                    archiveJarAction(archiveJarLocations)
                }
            }
        }

        if (after) {
            after(repo)
        }

        def gitCommitAuthor = sh(script: 'git show --summary | grep Author', returnStdout: true)
        return [repo, appVersion, gitCommitAuthor]
    }
}

def shapshotBuildForKafkaSchemaDeploy(BuilderImage buildImage, String environment, schemaPaths) {
  shapshotBuildForKafkaSchemaDeploy(buildImage.image, environment, schemaPaths)
}

def shapshotBuildForKafkaSchemaDeploy(String buildImage, String environment, schemaPaths) {
  def maven = new Maven()
  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def git = new Git()
  def ccloud = new CCloud()

  print "environment -> " + environment

  pod.node(
    containers: [ maven.containerTemplate(buildImage), ccloud.containerTemplate() ],
    volumes: [ maven.cacheVolume(), ssh.keysVolume() ]
  ) {
      def repo
      def appVersion
      stage('Non-Prod Deployment') {
          repo = gitScm.checkout()
          def pom = readMavenPom file: 'pom.xml'
          if (!pom.version) {
              error 'Please set project.version in pom.xml'
          }
          appVersion = (pom.version == '${revision}')?pom.properties.revision:pom.version
          currentBuild.displayName = "${repo.branch}-${appVersion}-${env.BUILD_NUMBER}-${environment}"
          def additionalOpts = getMavenAdditonalOptions(repo)
          maven.container {
              sh """
                export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
                mvn clean install
              """
          }
          ccloud.ccloudLogin()
          ccloud.uploadSchema(schemaPaths, environment)
      }

      return [repo, appVersion]
  }
}

def mavenAndNonprodMultiBuilds(config, buildImage) {
    def maven = new Maven()
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def ssh = new Ssh()
    def git = new Git()
    def docker = new Docker()
    def hadolint = new Hadolint()

    def image = buildImage.getImage()

    pod.node(
        containers: [maven.containerTemplate(image), docker.containerTemplate(), hadolint.containerTemplate()],
        volumes: [maven.cacheVolume(), ssh.keysVolume(), docker.daemonHostPathVolume()]
    ) {
        def repo
        def appVersion
        def map = [:]
        def archiveMap = [:]

        repo = gitScm.checkout()

        if (fileExists("./pom.xml")) {
          stage('Maven Snapshot') {
            def pom = readMavenPom file: 'pom.xml'
            appVersion = (pom.version == '${revision}')?pom.properties.revision:pom.version
            currentBuild.displayName = "${repo.branch}-${appVersion}-${env.BUILD_NUMBER}"
            maven.container {
              sh """
                export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
                mvn clean deploy -Dpmd.skip=true
              """
            }
          }
        }
        stage("Docker Build") {
          docker.container {
            config.dockerfiles.each{ dockerFile, tag ->
              def dockerfilefullpath = "${workspace}/${dockerFile}"
              hadolint.lintNoStage(dockerfilefullpath, "${workspace}/.hadolint.yaml")
              def nonprodImage = new NonprodDockerImage(repo, env, env.BUILD_NUMBER, null, tag)
              def labels = docker.standardLabels(repo)
              docker.build(labels: labels, image: nonprodImage.getImageWithAdditionalTagPart())
              docker.push(image: nonprodImage.getImageWithAdditionalTagPart())

              map[tag] = nonprodImage.getTagWithAdditionalTagPart()
            }
          }
        }
        appVersion = (null!=appVersion)?appVersion:config.releaseVersion
        return [repo, appVersion, map]
    }
}

def mavenReleaseMultiBuilds(config, buildImage) {

  def maven = new Maven()
  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def git = new Git()
  def docker = new Docker()
  def hadolint = new Hadolint()
  def releaseVersion
  def repo

  def image = buildImage.getImage()

  pod.node(
      containers: [maven.containerTemplate(image), docker.containerTemplate(), hadolint.containerTemplate()],
      volumes: [maven.cacheVolume(), ssh.keysVolume(), docker.daemonHostPathVolume()]
  ) {
      def map = [:]
      def archiveMap = [:]
      stage('Maven Release') {
          repo = gitScm.checkout()
          deleteDir()
          gitScm.checkout(repo, 'ghe-jenkins')
          sh "git reset --hard ${repo.commit}"
          def pom = readMavenPom file: 'pom.xml'
          if (!pom.artifactId) {
              error 'Please set project.artifactId in pom.xml'
          }
          def releaseBranch
          def releaseTag

          git.useJenkinsUser()
          releaseBranch = "release-${repo.branch}-${env.BUILD_NUMBER}"
          sh "git checkout -b ${releaseBranch} ${repo.branch}"
          sh "git push --set-upstream origin ${releaseBranch}"
          maven.container {
              git.useJenkinsUser()
              sh """
                export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
                mvn clean install -Dpmd.skip=true
                mvn release:prepare -DdryRun=true -Dpmd.skip=true
                mvn release:clean -Dpmd.skip=true
                mvn release:prepare -Dpmd.skip=true
                mvn release:perform -Dgoals=deploy -Dpmd.skip=true
              """
          }
          releaseTag = sh(returnStdout: true, script: "git describe --abbrev=0 --tags").trim()
          sh "git checkout ${releaseTag}"
          pom = readMavenPom file: 'pom.xml'
          releaseVersion = pom.version
          currentBuild.displayName = "${repo.branch}-${pom.version}-${env.BUILD_NUMBER}-released"
          def removeBranchName = ":${releaseBranch}"

          sh """
            git checkout $repo.branch
            git merge $releaseBranch
            git push -f
            ssh anthill@antprod1.westernasset.com -i /home/jenkins/.ssh/id_rsa '~/removeSnapshot.sh' $pom.artifactId
            git branch -D $releaseBranch
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $removeBranchName'
          """

      }
      stage("Docker Build") {
        docker.container {
          config.dockerfiles.each{ dockerFile, tag ->
            def dockerfilefullpath = "${workspace}/${dockerFile}"
            hadolint.lintNoStage(dockerfilefullpath, "${workspace}/.hadolint.yaml")
            def nonprodImage = new NonprodDockerImage(repo, env, env.BUILD_NUMBER, null, tag)
            def labels = docker.standardLabels(repo)
            docker.build(labels: labels, image: nonprodImage.getImageWithAdditionalTagPart())
            docker.push(image: nonprodImage.getImageWithAdditionalTagPart())

            map[tag] = nonprodImage.getTagWithAdditionalTagPart()
          }
        }
      }
      appVersion = (null!=appVersion)?appVersion:config.releaseVersion
      return [repo, appVersion, map]
  }
}

def snapshotBuildForDocker(config, buildImage) {
    def maven = new Maven()
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def ssh = new Ssh()
    def git = new Git()
    def docker = new Docker()
    def hadolint = new Hadolint()

    def image = buildImage.getImage()

    pod.node(
        containers: [maven.containerTemplate(image), docker.containerTemplate(), hadolint.containerTemplate()],
        volumes: [maven.cacheVolume(), ssh.keysVolume(), docker.daemonHostPathVolume()]
    ) {
        def repo
        def appVersion
        def map = [:]
        def archiveMap = [:]
        stage('Maven Snapshot') {
            repo = gitScm.checkout()
            def pom = readMavenPom file: 'pom.xml'
            if (!pom.version) {
                error 'Please set project.version in pom.xml'
            }

            List<String> archiveJarLocations = []
            config.kdaApps.each { item ->
              println item.flinkJarLocation
              archiveJarLocations.add(item.flinkJarLocation)
            }

            appVersion = (pom.version == '${revision}')?pom.properties.revision:pom.version
            currentBuild.displayName = "${repo.branch}-${appVersion}-${env.BUILD_NUMBER}"
            def additionalOpts = getMavenAdditonalOptions(repo)
            maven.container {
                sh """
                  export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
                  mvn clean $additionalOpts deploy -Dpmd.skip=true
                """
                if (archiveJarLocations != null) {
                    archiveMap = archiveJarAction(archiveJarLocations)
                }
            }
        }
        stage("Docker Build") {
          if (config.kubeApps != null) {
            for (kubeApp in config.kubeApps) {
              println kubeApp
              if (kubeApp.enabled) {
                println kubeApp.dockerFile
                def baseDir = kubeApp.dockerFile.split('/')[0]
                def nonprodImage = new NonprodDockerImage(repo, env, env.BUILD_NUMBER, baseDir, appVersion)
                docker.container {
                  docker.check("${workspace}/${kubeApp.dockerFile}")
                  hadolint.lintNoStage("${workspace}/${kubeApp.dockerFile}")
                  def labels = docker.standardLabels(repo)
                  docker.buildWithContext(labels: labels, image: nonprodImage.image, context: baseDir)
                  docker.push(image: nonprodImage.image)
                }
                map[baseDir]=nonprodImage.tag
              }
            }
          }
        }
        return [repo, appVersion, map, archiveMap]
    }
}

def releaseBuildForDocker(config, buildImage) {

  def maven = new Maven()
  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def git = new Git()
  def docker = new Docker()
  def hadolint = new Hadolint()
  def releaseVersion
  def repo

  def image = buildImage.getImage()

  pod.node(
      containers: [maven.containerTemplate(image), docker.containerTemplate(), hadolint.containerTemplate()],
      volumes: [maven.cacheVolume(), ssh.keysVolume(), docker.daemonHostPathVolume()]
  ) {
      def map = [:]
      def archiveMap = [:]
      stage('Maven Release') {
          repo = gitScm.checkout()
          deleteDir()
          gitScm.checkout(repo, 'ghe-jenkins')
          sh "git reset --hard ${repo.commit}"
          def pom = readMavenPom file: 'pom.xml'
          if (!pom.artifactId) {
              error 'Please set project.artifactId in pom.xml'
          }
          def releaseBranch
          def releaseTag

          List<String> archiveJarLocations = []
          config.kdaApps.each { item ->
            println item.flinkJarLocation
            archiveJarLocations.add(item.flinkJarLocation)
          }

          git.useJenkinsUser()
          releaseBranch = "release-${repo.branch}-${env.BUILD_NUMBER}"
          sh "git checkout -b ${releaseBranch} ${repo.branch}"
          sh "git push --set-upstream origin ${releaseBranch}"
          maven.container {
              git.useJenkinsUser()
              sh """
                export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
                mvn release:prepare -DdryRun=true -Dpmd.skip=true
                mvn release:clean -Dpmd.skip=true
                mvn release:prepare -Dpmd.skip=true
                mvn release:perform -Dgoals=deploy -Dpmd.skip=true
              """
              if (archiveJarLocations != null) {
                  archiveMap = archiveJarAction(archiveJarLocations)
              }
          }
          releaseTag = sh(returnStdout: true, script: "git describe --abbrev=0 --tags").trim()
          sh "git checkout ${releaseTag}"
          pom = readMavenPom file: 'pom.xml'
          releaseVersion = pom.version
          currentBuild.displayName = "${repo.branch}-${pom.version}-${env.BUILD_NUMBER}-released"
          def removeBranchName = ":${releaseBranch}"

          sh """
            git checkout $repo.branch
            git merge $releaseBranch
            git push -f
            ssh anthill@antprod1.westernasset.com -i /home/jenkins/.ssh/id_rsa '~/removeSnapshot.sh' $pom.artifactId
            git branch -D $releaseBranch
            ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $removeBranchName'
          """

      }
      stage("Docker Build") {
        if (config.kubeApps != null) {
          for (kubeApp in config.kubeApps) {
            println kubeApp
            if (kubeApp.enabled) {
              println kubeApp.dockerFile
              def baseDir = kubeApp.dockerFile.split('/')[0]
              def nonprodImage = new NonprodDockerImage(repo, env, env.BUILD_NUMBER, baseDir, releaseVersion)
              docker.container {
                docker.check("${workspace}/${kubeApp.dockerFile}")
                hadolint.lintNoStage("${workspace}/${kubeApp.dockerFile}")
                def labels = docker.standardLabels(repo)
                docker.buildWithContext(labels: labels, image: nonprodImage.image, context: baseDir)
                docker.push(image: nonprodImage.image)
              }
              map[baseDir]=nonprodImage.tag
            }
          }
        }
      }

      revisionRestore(repo)

      return [repo, releaseVersion, map, archiveMap]
  }
}

def pushImages(config, appVersion, crNumber, buildNumber, imageTagMap, branchName) {

  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def ssh = new Ssh()
  def git = new Git()
  def docker = new Docker()

  pod.node(
    containers: [ docker.containerTemplate()],
    volumes: [ ssh.keysVolume(), docker.daemonHostPathVolume()]
  ) {
    def repo
    def map = [:]
    repo = gitScm.checkout()

    currentBuild.displayName = "${branchName}-${appVersion}-${buildNumber}-${crNumber}"

    if (config.kubeApps != null) {
      for (kubeApp in config.kubeApps) {
        println kubeApp
        if (kubeApp.enabled) {
          println kubeApp.dockerFile
          def baseDir = kubeApp.dockerFile.split('/')[0]
          def nonprodTag = imageTagMap[baseDir]
          def crTag = nonprodTag + "-${crNumber}"
          docker.container {
            DockerImage nonprodImage = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${repo.organization}/${repo.safeName}", nonprodTag)
            DockerImage releaseImage = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${repo.organization}/${repo.safeName}", nonprodTag)
            DockerImage approveImage = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${repo.organization}/${repo.safeName}", crTag)

            docker.pullImage(nonprodImage.image)
            docker.tag(nonprodImage.image, approveImage.image)
            docker.push(approveImage.image)
            docker.tag(nonprodImage.image, releaseImage.image)
            docker.push(releaseImage.image)
          }

        }
      }
    }
  }
}

def archiveJarAction(List<String> archiveJarLocations) {
    def map =[:]
    for (archiveJarLocation in archiveJarLocations) {
      print archiveJarLocation
      print env.WORKSPACE
      def files = "${env.WORKSPACE}/${archiveJarLocation}/*.jar"
      def list = sh(returnStdout: true, script: "ls ${files}").split()
      def archiveFilename = ''
      list.each { item ->
        def file = item.trim().split('/').last()
        if (!file.toLowerCase().startsWith('original')) {
          archiveFilename = file
        }
      }
      archiveFilename = "${archiveJarLocation}/${archiveFilename}"
      archiveArtifacts(archiveFilename)
      map[archiveJarLocation] = archiveFilename
    }
    return map
}

def getMavenAdditonalOptions(repo) {
  def options = ''
  def branch = repo.branch
  if (!branch.contains('master') && !branch.contains('dev')) {
    try {
      def version = sh(returnStdout: true, script: "grep '<revision>' pom.xml").replace("<revision>","").replace("</revision>", "")
      if (version.contains('SNAPSHOT')) {
        modifiedVersion = version.replace('SNAPSHOT', repo.branch).trim() + '-SNAPSHOT'
      } else {
        modifiedVersion = version + "-${repo.branch}"
      }
      options = "-Drevision=${modifiedVersion}"
    } catch (e) {}
  }
  print options
  return options
}

def releaseBuild(GitRepository repo, BuilderImage builderImage, Closure after = null) {
    releaseBuild(repo, builderImage.image, after)
}

def releaseBuild(GitRepository repo, String buildImage, Closure after = null) {
    def jnlp = new Jnlp()
    def maven = new Maven()
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def ssh = new Ssh()
    def git = new Git()
    def releaseVersion

    pod.node(
        containers: [ jnlp.containerTemplate(), maven.containerTemplate(buildImage) ],
        volumes: [ maven.cacheVolume(), ssh.keysVolume() ]
    ) {
        stage('Maven Release') {
            deleteDir()
            gitScm.checkout(repo, 'ghe-jenkins')
            sh "git reset --hard ${repo.commit}"
            def pom = readMavenPom file: 'pom.xml'
            if (!pom.artifactId) {
                error 'Please set project.artifactId in pom.xml'
            }
            def releaseBranch
            def releaseTag

            git.useJenkinsUser()
            releaseBranch = "release-${repo.branch}-${env.BUILD_NUMBER}"
            sh "git checkout -b ${releaseBranch} ${repo.branch}"
            sh "git push --set-upstream origin ${releaseBranch}"
            maven.container {
                git.useJenkinsUser()
                sh """
                  export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
                  mvn release:prepare -DdryRun=true
                  mvn release:clean
                  mvn release:prepare
                  mvn release:perform -Dgoals=deploy
                """
            }
            releaseTag = sh(returnStdout: true, script: "git describe --abbrev=0 --tags").trim()
            sh "git checkout ${releaseTag}"
            pom = readMavenPom file: 'pom.xml'
            releaseVersion = pom.version
            currentBuild.displayName = "${repo.branch}-${pom.version}-${env.BUILD_NUMBER}-released"
            def removeBranchName = ":${releaseBranch}"

            sh """
              git checkout $repo.branch
              git merge $releaseBranch
              git push -f
              ssh anthill@antprod1.westernasset.com -i /home/jenkins/.ssh/id_rsa '~/removeSnapshot.sh' $pom.artifactId
              git branch -D $releaseBranch
              ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push origin $removeBranchName'
            """

            revisionRestore(repo)

        }

        return [repo, releaseVersion]
    }
}

def revisionRestore(repo) {

  deleteDir()
  git url: "${repo.scm}", credentialsId: 'ghe-jenkins', branch: "${repo.branch}"

  def version_old = findRevisionVersion()
  if (version_old?.trim()) {
    pom = readMavenPom file: 'pom.xml'
    def version_new = pom.version

    print pom

    println "old version ->" + version_old
    println "new version ->" + version_new

    def filelist = sh(returnStdout: true, script: "find . -name 'pom.xml'")
    println filelist
    def fileSet = filelist.split('\n')
    for(f in fileSet) {
      if (f?.trim() && !f.contains('target')) {
        def content = readFile file: "${f}"
        if (content.contains("revision")) {
          content = content.replace("<revision>${version_old}</revision>", "<revision>${version_new}</revision>")
        }
        content = content.replace("<version>${version_new}</version>", '<version>${revision}</version>')
        writeFile file: "${f}", text: content
      }
      sh """
        git add $f
      """
    }
    sh """
      git commit -m "Restore revison settings in the pom by Jenkis build process"
      ssh-agent sh -c 'ssh-add ~/.ssh/ghe-jenkins; git push -f'
    """
  }

}

def findRevisionVersion() {
  def version
  try {
    version = sh(returnStdout: true, script: "grep '<revision>' pom.xml").replace("<revision>","").replace("</revision>", "")
    version = version.trim()
  } catch (e) {}
  return version
}

def mavenSiteDeploy(builderTag, scm, branch) {

  def builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  podTemplate(
    cloud: 'pas-development',
    serviceAccount: 'jenkins',
    namespace: 'devops-jenkins',
    containers: [
      containerTemplate(name: 'jnlp', image: "${env.TOOL_AGENT}", args: '${computer.jnlpmac} ${computer.name}'),
      containerTemplate(name: 'maven', image: "${builderImage}", ttyEnabled: true, command: 'cat')
    ],
    volumes: [
      persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
      persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
    ]) {
    node(POD_LABEL) {
      def appVersion
      stage('Maven Site') {
        deleteDir()
        git url: "${scm}", credentialsId: 'ghe-jenkins', branch: "${branch}"

        def pom = readMavenPom file: 'pom.xml'
        if (!pom.version) {
          error 'Please set project.version in pom.xml'
        }
        appVersion = (pom.version == '${revision}')?pom.properties.revision:pom.version
        currentBuild.displayName = "${branch}-${appVersion}-${env.BUILD_NUMBER}"
        container('maven') {
          sh """
            export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
            mvn --batch-mode -Dpmd.skip=true clean install site-deploy
          """
        }
      }
    }
  }
}

return this
