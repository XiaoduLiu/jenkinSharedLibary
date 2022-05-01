#!/usr/bin/groovy

import com.aristotlecap.pipeline.builds.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.models.*

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  final String PROJECT_TYPE = 'gradleDockerService'

  def pod = new PodTemplate()
  def gitScm = new GitScm()
  def gradle = new Gradle()
  def maven = new Maven()
  def docker = new Docker()
  def vault = new Vault()
  def ssh = new Ssh()
  def kubectl = new Kubectl()
  def prompt = new Prompt()
  def conditionals = new Conditionals()
  def git = new Git()

  def dockerBuild = new DockerBuild()
  def gradleBuild = new GradleBuild()

  BuilderImage buildImage = config.buildImage != null ? BuilderImage.fromImage(config.builderImage) : BuilderImage.fromTag(env, config.builderTag)

  def repo
  def nonprodImage

  conditionals.lockWithLabel {
    gradleBuild.nonprodBuild(buildImage.image)
    (repo, nonprodImage) = dockerBuild.nonprodBuild()
  }

  def environment = prompt.nonprod(config.nonProdEnvs, config.qaEnvs);
  if (environment == null || environment.isEmpty()) {
    return // Stop if user aborts or timeout
  }

  pod.node(
    containers: [
      gradle.containerTemplate(buildImage.image),
      kubectl.containerTemplate(),
      vault.containerTemplate()
    ],
    volumes: [
      ssh.keysVolume()
    ]
  ) {

    gradle.container {
      stage('Checkout') {
        repo = gitScm.checkout()
      }
    }

    stage('Generate Secrets') {
      conditionals.when(config.templates instanceof Map) {
        def secrets

        vault.container {
          secrets = vault.processTemplates(repo, environment, config.templates)
        }

        kubectl.container {
          kubectl.createSecret(repo, environment, secrets)
        }
      }
    }

    stage('Deploy') {
      kubectl.deploy(environment, repo, nonprodImage.image)
    }
  }

  if (!config.qaEnvs.contains(environment)) {
    return // Stop if deployed environment is not pre-production
  }

  def crNumber = prompt.changeRequest()
  if (crNumber == null || crNumber.isEmpty()) {
    return // Stop if change request is not set
  }

  ProdDockerImages prodImages = new ProdDockerImages(repo, env, config.releaseVersion, environment)

  // Trigger prod deploy job

  pod.node(
    containers: [
      docker.containerTemplate()
    ],
    volumes: [
      docker.daemonHostPathVolume(),
      ssh.keysVolume()
    ]
  ) {
    gitScm.checkout(repo, 'ghe-jenkins')

    def gitReleaseTag = "${repo.safeName}-${config.releaseVersion}"

    git.useJenkinsUser()
    sh "git tag -a $gitReleaseTag -m \"Release for ${crNumber}\""
    sh "git push origin $gitReleaseTag"

    docker.container {
      docker.pull(image: nonprodImage.image)

      docker.tag(nonprodImage.image, prodImages.approveImage)
      docker.tag(nonprodImage.image, prodImages.releaseImage)
      docker.tag(nonprodImage.image, prodImages.releaseCrImage)

      docker.push(image: prodImages.approveImage)
      docker.push(image: prodImages.releaseImage)
      docker.push(image: prodImages.releaseCrImage)
    }
  }

  def parameters = [
    [$class: 'StringParameterValue', name: 'buildNumber', value: String.valueOf(env.BUILD_NUMBER)],
    [$class: 'StringParameterValue', name: 'releaseVersion', value: String.valueOf(config.releaseVersion)],
    [$class: 'StringParameterValue', name: 'crNumber', value: String.valueOf(crNumber)],
    [$class: 'StringParameterValue', name: 'gitBranchName', value: String.valueOf(repo.branch)],
    [$class: 'StringParameterValue', name: 'gitCommit', value: String.valueOf(repo.commit)],
    [$class: 'StringParameterValue', name: 'gitScm', value: String.valueOf(repo.scm)],
    [$class: 'StringParameterValue', name: 'organizationName', value: String.valueOf(repo.organization)],
    [$class: 'StringParameterValue', name: 'appGitRepoName', value: String.valueOf(repo.name)],
    [$class: 'StringParameterValue', name: 'appDtrRepo', value: String.valueOf(repo.dtr)],
    [$class: 'StringParameterValue', name: 'prodEnv', value: String.valueOf(config.prodEnv)],
    [$class: 'StringParameterValue', name: 'drEnv', value: String.valueOf(config.drEnv)],
    // [$class: 'StringParameterValue', name: 'liquibaseChangeLog', value: String.valueOf(liquibaseChangeLog)],
    // [$class: 'StringParameterValue', name: 'liquibaseBuilderTag', value: String.valueOf(liquibaseBuilderTag)],
    // [$class: 'StringParameterValue', name: 'secrets', value: String.valueOf(secrets)],
    [$class: 'StringParameterValue', name: 'projectType', value: String.valueOf(PROJECT_TYPE)]
    // [$class: 'StringParameterValue', name: 'postDeploySteps', value: String.valueOf(postDeploySteps)],
    // [$class: 'StringParameterValue', name: 'dockerfileToTagMap', value: String.valueOf(dockerfileToTagMapString)]
  ]

  if (config.templates != null) {
    parameters.add([$class: 'StringParameterValue', name: 'templates', value: JSONObject.fromObject(config.templates).toString()])
  }

  stage('Trigger Ops Release') {
    build job: env.opsReleaseJob, wait: false, parameters: parameters
  }
}
