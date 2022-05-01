package com.aristotlecap.pipeline.builds

import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.models.*


def nonprodMultiBuilds(config) {
    def docker = new Docker()
    def hadolint = new Hadolint()
    def ssh = new Ssh()
    def pod = new PodTemplate()
    def gitScm = new GitScm()

    pod.node(
        containers: [
            docker.containerTemplate(),
            hadolint.containerTemplate(),
        ],
        volumes: [
            ssh.keysVolume(),
            docker.daemonHostPathVolume()
        ]
    ) {
        def repo = gitScm.checkout()
        def map = [:]

        docker.container {
          stage("Docker Build") {
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

        return [repo, map]
    }
}

def nonprodBuild() {
    def docker = new Docker()
    def hadolint = new Hadolint()
    def ssh = new Ssh()
    def pod = new PodTemplate()
    def gitScm = new GitScm()

    pod.node(
        containers: [
            docker.containerTemplate(),
            hadolint.containerTemplate(),
        ],
        volumes: [
            ssh.keysVolume(),
            docker.daemonHostPathVolume()
        ]
    ) {
        def repo
        def nonprodImage

        stage('Docker Checkout') {
            repo = gitScm.checkout()
            nonprodImage = new NonprodDockerImage(repo, env, env.BUILD_NUMBER)
        }

        docker.container {
            stage('Docker Check') {
                docker.check()
                hadolint.lint()
            }
            stage('Docker Build') {
                def labels = docker.standardLabels(repo)
                docker.build(labels: labels, image: nonprodImage.image)
            }
            stage('Docker Push') {
                docker.push(image: nonprodImage.image)
            }

            return [repo, nonprodImage]
        }
    }
}

def pushProdImages(GitRepository repo, DockerImage nonprodImage, String releaseVersion, String changeRequest) {
    def docker = new Docker()
    def vault = new Vault()
    def hadolint = new Hadolint()
    def ssh = new Ssh()
    def pod = new PodTemplate()
    def gitScm = new GitScm()

    pod.node(
        containers: [
            vault.containerTemplate(),
            docker.containerTemplate(),
            hadolint.containerTemplate(),
        ],
        volumes: [
            ssh.keysVolume(),
            docker.daemonHostPathVolume()
        ]
    ) {
        gitScm.checkout(repo)
        docker.container {
            String crTag = "${releaseVersion}-${changeRequest}"
            DockerImage approveImage = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${repo.organization}/${repo.safeName}", crTag)
            DockerImage releaseImage1 = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${repo.organization}/${repo.safeName}", releaseVersion)
            DockerImage releaseImage2 = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${repo.organization}/${repo.safeName}", crTag)

            docker.tag(nonprodImage.image, approveImage.image)
            docker.push(approveImage.image)
            docker.tag(nonprodImage.image, releaseImage1.image)
            docker.push(releaseImage1.image)
            docker.tag(nonprodImage.image, releaseImage2.image)
            docker.push(releaseImage2.image)

            return [approveImage, releaseImage1, releaseImage2]
        }
    }
}

def pushMultiProdImages(GitRepository repo, tagMap, releaseVersion, changeRequest) {

  def docker = new Docker()
  def vault = new Vault()
  def hadolint = new Hadolint()
  def ssh = new Ssh()
  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def map = [:]

  pod.node(
      containers: [
          vault.containerTemplate(),
          docker.containerTemplate(),
          hadolint.containerTemplate(),
      ],
      volumes: [
          ssh.keysVolume(),
          docker.daemonHostPathVolume()
      ]
  ) {
      gitScm.checkout(repo)
      docker.container {
        tagMap.each{ key, value ->
            String crTag = "${value}-${changeRequest}"

            print key
            print value
            print crTag

            DockerImage nonprodImage = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${repo.organization}/${repo.safeName}", value)
            DockerImage approveImage = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_NONPROD_KEY}/${repo.organization}/${repo.safeName}", crTag)
            DockerImage releaseImage = new BasicDockerImage("${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${repo.organization}/${repo.safeName}", crTag)

            docker.pullImage(nonprodImage.image)
            docker.tag(nonprodImage.image, approveImage.image)
            docker.push(approveImage.image)
            docker.tag(nonprodImage.image, releaseImage.image)
            docker.push(releaseImage.image)

            map[key] = crTag

        }
    }
    return map
  }
}

return this
