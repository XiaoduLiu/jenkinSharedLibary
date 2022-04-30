package com.westernasset.pipeline.steps

def container(Closure body) {
    container('gradle') {
        return body()
    }
}

def containerTemplate(String image) {
    return containerTemplate(name: 'gradle', image: image, ttyEnabled: true)
}

def taskExists(String task) {
    return sh(script: "./gradlew help --task ${task} > /dev/null 2>&1", returnStatus: true) == 0
}

def jar() {
    sh './gradlew jar'
}

def check() {
    try {
        sh "./gradlew check"
    }
    catch (Throwable e) {
        println e
    }
    finally {
        junit "**/build/test-results/**/*.xml"
    }
}

def publish() {
    sh './gradlew publish'
}

return this
