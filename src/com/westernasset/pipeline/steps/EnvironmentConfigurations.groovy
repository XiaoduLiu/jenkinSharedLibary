package com.westernasset.pipeline.steps

import java.util.regex.Matcher

import com.westernasset.pipeline.models.GitRepository
import com.westernasset.pipeline.models.DockerImage

Map get(String environment, GitRepository git, String tag) {
    Map envMap = [:]

    if (fileExists("conf/env/${environment}.groovy")) {
        envMap << load("conf/env/${environment}.groovy").envMap
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
    envMap.SPLUNK_TAG = env.SPLUNK_TAG

    // if (dockerfileToTagMap != null) {
    //   def dkrfToTagMap = getMapFromString(dockerfileToTagMap)
    //   dkrfToTagMap.each{ key, value ->
    //     imageTagMap = "${gitBranchName}-${value}-${buildNumber}"
    //     envMap[value.toString()] = imageTagMap.toString()
    //   }
    // }

    return envMap
}

Map get(String environment, GitRepository git, String tag, String baseFolder) {
    Map envMap = [:]

    if (fileExists("${baseFolder}/conf/env/${environment}.groovy")) {
        envMap << load("${baseFolder}/conf/env/${environment}.groovy").envMap
    }

    if (fileExists("${baseFolder}/conf/env/${environment}.properties")) {
        envMap << readProperties(file: "${baseFolder}/conf/env/${environment}.properties")
    }

    envMap.TAG = tag
    envMap.ORG = git.organization
    envMap.REPO = git.safeName
    envMap.IMAGEHUB = env.IMAGE_REPO_URI
    envMap.ENV = environment
    envMap.REPO_KEY = environment == 'prod' ? env.IMAGE_REPO_PROD_KEY : env.IMAGE_REPO_NONPROD_KEY
    envMap.SPLUNK_TAG = env.SPLUNK_TAG

    // if (dockerfileToTagMap != null) {
    //   def dkrfToTagMap = getMapFromString(dockerfileToTagMap)
    //   dkrfToTagMap.each{ key, value ->
    //     imageTagMap = "${gitBranchName}-${value}-${buildNumber}"
    //     envMap[value.toString()] = imageTagMap.toString()
    //   }
    // }

    return envMap
}

void apply(Map properties, String glob = 'kubernetes/*') {
    findFiles(glob: glob).each { file ->
        String content = readFile(file: file.path)
        content = applyProperties(content, properties)
        writeFile(file: file.path, text: content)
        sh "cat ${file.path}"
    }
}

String applyProperties(String text, Map properties) {
  String content = text
  properties.each { k, v ->
    content = content.replaceAll('\\$\\{' + k.toString() + '\\}', Matcher.quoteReplacement(v))
  }
  return content
}

return this
