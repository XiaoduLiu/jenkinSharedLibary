package com.westernasset.pipeline.steps

def container(Closure body) {
    container('maven') {
        return body()
    }
}

def containerTemplate(String image) {
    return containerTemplate(name: 'maven', image: image, ttyEnabled: true)
}

def cacheVolume() {
    return persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2')
}

def effectiveSettings() {
    sh "mvn -B help:effective-settings"
}

def clean() {
    sh "mvn -B clean"
}

def deploy() {
    sh """
      export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
      mvn -B deploy
    """
}

def releaseClean() {
    sh "mvn -B release:clean"
}

def releasePrepare(Map args = [:]) {
    def dryRunArg = (args.dryRun && args.dryRun == true) ? '-DdryRun=true' : ''
    sh """
      export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
      mvn -B release:prepare $dryRunArg
    """
}

def releasePerform() {
    sh """
      export MAVEN_OPTS='-Xmx6144m -XX:MaxPermSize=512m -Xss320m'
      mvn -B release:perform
    """
}

return this
