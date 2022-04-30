package com.westernasset.pipeline.steps

import com.westernasset.pipeline.models.GitRepository
import com.westernasset.pipeline.models.NonprodDockerImage
import com.westernasset.pipeline.models.DockerImage

def container(Closure body) {
    container('docker') {
        body()
    }
}

def containerTemplate(String image = env.TOOL_DOCKER) {
    return containerTemplate(name: 'docker', image: image, ttyEnabled: true)
}

def daemonHostPathVolume() {
    return hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
}

def check(String dockerfile = "${workspace}/Dockerfile") {
    if (!fileExists(dockerfile)) {
      error("Dockerfile not found!")
    }
}

def lint(String dockerfile = "${workspace}/Dockerfile") {
    def statusCode = sh(label: 'Lint Dockerfile', returnStatus: true, script: "docker run --rm -i ${env.TOOL_HADOLINT} < ${dockerfile}")
    if (statusCode > 0) {
        unstable('Dockerfile linter errors found')
    }
}

Map<String, String> standardLabels(GitRepository git) {
    Map<String, String> labels = [:]

    labels['com.westernasset.github.org'] = git.organization
    labels['com.westernasset.github.repo'] = git.name
    labels['com.westernasset.github.branch'] = git.branch
    labels['com.westernasset.github.hash'] = git.commit

    def timestamp = new Date().format("yyyy-MM-dd HH:mm:ss'('zzz')'")
    labels['com.westernasset.buildTime'] = "'${timestamp.toString()}'"

    return labels
}

String toCmdLineArgsFromLabels(Map<String, String> labels) {
    List pairs = labels.collect { label -> "--label ${label.key}=${label.value}" }
    return pairs.join(' ')
}

void build(Map args) {
    String wk = (args.context != null) ? "${workspace}/${args.context}":"${workspace}"
    String dockerfile = args.dockerfile ?: "${wk}/Dockerfile"
    String context = args.context ?: '.'
    String imageArgs = args.image ? "-t ${args.image}" : ''
    String labelArgs = args.labels ? toCmdLineArgsFromLabels(args.labels) : ''

    sh "docker build -f ${dockerfile} ${imageArgs} ${labelArgs} ${context}"
}

void buildWithContext(Map args) {
    String wk = (args.context != null) ? "${workspace}/${args.context}":"${workspace}"
    String dockerfile = args.dockerfile ?: "${wk}/Dockerfile"
    String context = args.context ?: '.'
    String imageArgs = args.image ? "-t ${args.image}" : ''
    String labelArgs = args.labels ? toCmdLineArgsFromLabels(args.labels) : ''

    sh """
      cd ./$context
      pwd
      docker build -f ${dockerfile} ${imageArgs} ${labelArgs} .
    """
}

void tag(DockerImage source, DockerImage target) {
    tag(source.image, target.image)
}

void tag(String source, String target) {
    sh "docker tag ${source} ${target}"
}

void pull(Map args) {
    String registry = args.registry ?: env.IMAGE_REPO_URI
    String url = args.url ?: "https://${registry}"
    String credentials = args.credentials ?: env.IMAGE_REPO_CREDENTIAL

    docker.withRegistry(url, credentials) {
        sh "docker pull ${args.image}"
    }
}

void pullImage(String image) {
  String registry = env.IMAGE_REPO_URI
  String url = "https://${registry}"
  String credentials = env.IMAGE_REPO_CREDENTIAL

  docker.withRegistry(url, credentials) {
    sh "docker pull ${image}"
  }
}

void push(String image) {
    push(image: image)
}

void push(Map args) {
    String registry = args.registry ?: env.IMAGE_REPO_URI
    String url = args.url ?: "https://${registry}"
    String credentials = args.credentials ?: env.IMAGE_REPO_CREDENTIAL

    docker.withRegistry(url, credentials) {
        sh "docker push ${args.image}"
    }
}

return this
