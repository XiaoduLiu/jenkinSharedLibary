package com.westernasset.pipeline.steps

import com.cloudbees.groovy.cps.NonCPS
import java.util.regex.Matcher

import com.westernasset.pipeline.models.GitRepository
import com.westernasset.pipeline.models.DockerImage

def container(Closure body) {
    container('hadolint') {
        return body()
    }
}

def containerTemplate(String image = env.TOOL_HADOLINT) {
    return containerTemplate(name: 'hadolint', image: image, ttyEnabled: true)
}

def lint(String dockerfile = "${workspace}/Dockerfile", String hadolintConfig = "${workspace}/.hadolint.yaml") {
    stage('Linter Dockerfile') {
        container {
            String hadolintArgs = fileExists(hadolintConfig) ? "--config ${hadolintConfig}" : "--ignore DL3015 --ignore DL3008"
            def statusCode = sh(label: 'Lint Dockerfile', returnStatus: true, script: "hadolint ${hadolintArgs} ${dockerfile}")
            if (statusCode > 0) {
                unstable('Dockerfile linter errors found')
            }
        }
    }
}

def lintNoStage(String dockerfile = "${workspace}/Dockerfile", String hadolintConfig = "${workspace}/.hadolint.yaml") {
    container {
        String hadolintArgs = fileExists(hadolintConfig) ? "--config ${hadolintConfig}" : "--ignore DL3015 --ignore DL3008"
        def statusCode = sh(label: 'Lint Dockerfile', returnStatus: true, script: "hadolint ${hadolintArgs} ${dockerfile}")
        if (statusCode > 0) {
            unstable('Dockerfile linter errors found')
        }
    }
}

return this
