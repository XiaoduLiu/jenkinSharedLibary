package com.westernasset.pipeline.steps

import com.cloudbees.groovy.cps.NonCPS
import java.util.regex.Matcher

import com.westernasset.pipeline.models.GitRepository
import com.westernasset.pipeline.models.DockerImage

def getEnvProperties(String environment, GitRepository git, String tag) {
    def envMap = [:]

    if (fileExists("conf/env/${environment}.groovy")) {
        def tempScript = load "conf/env/${environment}.groovy"
        envMap << tempScript.getEnvMap()
    }

    if (fileExists("conf/env/${environment}.properties")) {
        envMap << readProperties(file: "conf/env/${environment}.properties")
    }

    envMap.TAG = tag
    envMap.ORG = git.organization
    envMap.REPO = git.safeName
    envMap.IMAGEHUB = env.IMAGE_REPO_URI
    envMap.ENV = environment
    envMap.REPO_KEY = environment == 'prod' ? env.IMAGE_REPO_PROD_KEY : env.IMAGE_REPO_NONPROD_KEY

    // if (dockerfileToTagMap != null) {
    //   def dkrfToTagMap = getMapFromString(dockerfileToTagMap)
    //   dkrfToTagMap.each{ key, value ->
    //     imageTagMap = "${gitBranchName}-${value}-${buildNumber}"
    //     envMap[value.toString()] = imageTagMap.toString()
    //   }
    // }

    return envMap
}

def getEnvProperties(String environment, GitRepository git, Map tagMap) {
    def envMap = [:]

    if (fileExists("conf/env/${environment}.groovy")) {
        def tempScript = load "conf/env/${environment}.groovy"
        envMap << tempScript.getEnvMap()
    }

    if (fileExists("conf/env/${environment}.properties")) {
        envMap << readProperties(file: "conf/env/${environment}.properties")
    }

    envMap.ORG = git.organization
    envMap.REPO = git.safeName
    envMap.IMAGEHUB = env.IMAGE_REPO_URI
    envMap.ENV = environment
    envMap.REPO_KEY = environment == 'prod' ? env.IMAGE_REPO_PROD_KEY : env.IMAGE_REPO_NONPROD_KEY

    if (tagMap != null) {
       tagMap.each{ tag, value ->
         envMap[tag] = value
       }
    }

    print envMap

    return envMap
}

@NonCPS
int kindOrder(String text) {
    List<String> kinds = [
        'Namespace',
        'PodPreset',
        'ResourceQuota',
        'StorageClass',
        'CustomResourceDefinition',
        'ServiceAccount',
        'PodSecurityPolicy',
        'Role',
        'ClusterRole',
        'RoleBinding',
        'ClusterRoleBinding',
        'ConfigMap',
        'Secret',
        'Service',
        'LimitRange',
        'PriorityClass',
        'Deployment',
        'StatefulSet',
        'CronJob',
        'PodDisruptionBudget'
    ]

    int index = kinds.indexOf(text)
    return index >= 0 ? index : kinds.size()
}

@NonCPS
int kubeYamlOrder(def content) {
    if (content instanceof Iterable) {
        def lowestOrderResource = content.min { a, b -> kindOrder(a.kind) <=> kindOrder(b.kind) }
        return kindOrder(lowestOrderResource.kind)
    } else {
        return kindOrder(content.kind)
    }
}

@NonCPS
Map sort(Map fileYamls) {
    return fileYamls.sort { a, b -> kubeYamlOrder(a.value) <=> kubeYamlOrder(b.value) }
}

@NonCPS
boolean canBePatched(def resource) {
    return resource.kind != 'PodPreset'
}

void delete(def resource) {
    String kind = resource.kind
    String name = resource.metadata.name
    String namespaceArg = resource.metadata.namespace != null ? "-n ${resource.metadata.namespace}" : ''
    if (sh(returnStatus: true, script: "kubectl get ${kind} ${name} ${namespaceArg}") == 0) {
        sh "kubectl delete ${kind} ${name} ${namespaceArg}"
    }
}

def deployKubernetes(String glob = 'kubernetes/*.yaml') {
    def fileYamls = [:]
    findFiles(glob: glob).each { file ->
        fileYamls[file.path] = readYaml(file: file.path)
    }
    sort(fileYamls).each { path, content ->
        def resources = content instanceof Iterable ? content : [content]
        resources.each { resource ->
            if (!canBePatched(resource)) {
                delete(resource)
            }
        }
        apply(path: path, force: false)
    }
}

def apply(Map args) {
    if (args.force == true) {
        def resource = readYaml file: args.path
        delete(resource)
    }
    sh "kubectl apply -f ${args.path}"
}

def apply(String path) {
    apply([path: path, force: true])
}

void deploy(String glob = 'kubernetes/*.y*ml') {
    container {
        deployKubernetes(glob)
    }
}

def deploy(String environment, GitRepository git, String tag, String glob = 'kubernetes/*.yaml') {
    container {
        renderKubernetesYaml(getEnvProperties(environment, git, tag), glob)
        deployKubernetes(glob)
    }
}

def deploy(String environment, GitRepository git, Map tagMap, String glob = 'kubernetes/*.yaml') {
    container {
        def map = (null!=tagMap)?tagMap:[:]
        renderKubernetesYaml(getEnvProperties(environment, git, map), glob)
        deployKubernetes(glob)
    }
}

def deploy(String environment, GitRepository git, DockerImage dockerImage, String glob = 'kubernetes/*.yaml') {
    deploy(environment, git, dockerImage.image, glob)
}

def renderKubernetesYaml(def properties, String glob = 'kubernetes/*.yaml') {
    findFiles(glob: glob).each { file ->
        def content = readFile(file: file.path)
        content = applyProperties(content, properties)
        writeFile(file: file.path, text: content)
        sh "cat ${file.path}"
    }
}

def applyProperties(def text, def properties) {
  properties.each { k, v ->
    text = text.replaceAll('\\$\\{' + k.toString() + '\\}', Matcher.quoteReplacement(v))
  }
  return text
}

def container(Closure body) {
    container('kubectl') {
        body()
    }
}

def containerTemplate(String image = env.TOOL_KUBECTL) {
    return containerTemplate(name: 'kubectl', image: image, ttyEnabled: true)
}

def createSecret(GitRepository repo, String appBaseDir, String env, Collection files) {
  String namespace = repo.organization + '-default'
  String name = repo.organization + '-' + repo.safeName + '-' + appBaseDir + '-' + env + '-secret'
  createSecret(namespace, name, files)
}

def createSecret(GitRepository repo, String env, Collection files) {
    String namespace = repo.organization + '-default'
    String name = repo.organization + '-' + repo.safeName + '-' + env + '-secret'
    createSecret(namespace, name, files)
}

def createSecret(String namespace, String name, Collection files) {
    def statusCode = sh(returnStatus: true, script: "kubectl get secret -n ${namespace} ${name}")
    if (statusCode == 0) {
        sh "kubectl delete secret -n ${namespace} ${name}"
    }
    def fromFileArgs = files.collect { file -> "--from-file=${file}" }.join(' ')
    sh "kubectl create secret generic -n ${namespace} ${name} ${fromFileArgs}"
}

def createSecretIfNew(String namespace, String name, Collection files) {
    def statusCode = sh(returnStatus: true, script: "kubectl get secret -n ${namespace} ${name}")
    if (!(statusCode == 0)) {
      def fromFileArgs = files.collect { file -> "--from-file=${file}" }.join(' ')
      sh "kubectl create secret generic -n ${namespace} ${name} ${fromFileArgs}"
    }
}

return this
