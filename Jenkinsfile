@Library('wam-pipelines') _

import com.westernasset.pipeline.models.GitRepository
import com.westernasset.pipeline.steps.*

def pod = new PodTemplate()
def gradle = new Gradle()
def maven = new Maven()
def gitScm = new GitScm()
def ssh = new Ssh()

String buildImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:j8u212-m3.6.1-s6.5.1.240"

pod.node(
    containers: [
      gradle.containerTemplate(buildImage),
    ],
    volumes: [
        maven.cacheVolume(),
        ssh.keysVolume()
    ]
) {
    gradle.container {
        stage('Checkout') {
            gitScm.checkout()
        }
        stage('Check') {
            gradle.check()
        }
    }
}
