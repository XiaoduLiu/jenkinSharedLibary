package com.aristotlecap.pipeline.builds

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*

def nonprodBuild(String buildImage, Closure after = null) {
    def maven = new Maven()
    def gradle = new Gradle()
    def ssh = new Ssh()
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def conditionals = new Conditionals()

    pod.node(
        containers: [
            gradle.containerTemplate(buildImage)
        ],
        volumes: [
            maven.cacheVolume()
        ]
    ) {
        GitRepository repo

        gradle.container {
            stage('Gradle Build') {
                repo = gitScm.checkout()
                gradle.check()
                gradle.jar()
            }
            stage('Gradle Publish') {
                conditionals.when(gradle.taskExists('publish')) {
                    gradle.publish()
                }
            }
        }

        if (after) {
            after(repo)
        }

        return [repo]
    }
}

return this
