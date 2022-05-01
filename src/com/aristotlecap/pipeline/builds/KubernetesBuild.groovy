package com.aristotlecap.pipeline.builds

import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.models.*

void build(GitRepository repo, String environment, DockerImage image, Map secrets, String key = '') {
    Ssh ssh = new Ssh()
    PodTemplate pod = new PodTemplate()
    GitScm gitScm = new GitScm()
    Vault vault = new Vault()
    Kubectl kubectl = new Kubectl()
    Conditionals conditionals = new Conditionals()

    pod.node(
        containers: [ kubectl.containerTemplate(), vault.containerTemplate() ],
        volumes: [ ssh.keysVolume() ]
    ) {
        stage('Checkout') {
            gitScm.checkout(repo)
        }

        stage('Generate Secrets') {
            conditionals.when(secrets != null && secrets.size() > 0) {
                def processedSecrets

                vault.container {
                    processedSecrets = vault.processTemplates(repo, environment, secrets)
                }

                kubectl.container {
                    kubectl.createSecret(repo, environment, processedSecrets)
                }
            }
        }

        stage('Deploy') {
            kubectl.deploy(environment, repo, image.tag)
        }
    }

}

void nonprodBuild(GitRepository repo, String environment, DockerImage image, Map secrets = [:]) {
    Ssh ssh = new Ssh()
    PodTemplate pod = new PodTemplate()
    GitScm gitScm = new GitScm()
    Vault vault = new Vault()
    Kubectl kubectl = new Kubectl()
    Conditionals conditionals = new Conditionals()

    pod.node(
        containers: [ kubectl.containerTemplate(), vault.containerTemplate() ],
        volumes: [ ssh.keysVolume() ]
    ) {
        stage('Checkout') {
            gitScm.checkout(repo)
        }

        stage('Generate Secrets') {
            conditionals.when(secrets != null && secrets.size() > 0) {
                def processedSecrets

                vault.container {
                    processedSecrets = vault.processTemplates(repo, environment, secrets)
                }

                kubectl.container {
                    kubectl.createSecret(repo, environment, processedSecrets)
                }
            }
        }

        stage('Deploy') {
            kubectl.deploy(environment, repo, image.tag)
        }
    }
}

void prodBuild(GitRepository repo, String environment, String target, String tag, Map secrets = [:]) {
    Ssh ssh = new Ssh()
    PodTemplate pod = new PodTemplate()
    GitScm gitScm = new GitScm()
    Vault vault = new Vault()
    Kubectl kubectl = new Kubectl()
    Conditionals conditionals = new Conditionals()

    pod.node(
        cloud: target,
        containers: [ kubectl.containerTemplate(), vault.containerTemplate() ],
        volumes: [ ssh.prodKeysVolume() ]
    ) {
        stage('Checkout') {
            gitScm.checkout(repo, 'ghe-jenkins')
        }

        stage('Generate Secrets') {
            conditionals.when(secrets != null && secrets.size() > 0) {
                def processedSecrets

                vault.container {
                    processedSecrets = vault.processTemplates(repo, environment, secrets)
                }

                kubectl.container {
                    kubectl.createSecret(repo, environment, processedSecrets)
                }
            }
        }

        stage('Deploy') {
            kubectl.deploy(environment, repo, tag)
        }
    }
}

//for helm deploy kubernetes process, in case we need CRD, etc.
void build(GitRepository repo, String environment, imageMap, Map secrets) {
    Ssh ssh = new Ssh()
    PodTemplate pod = new PodTemplate()
    GitScm gitScm = new GitScm()
    Vault vault = new Vault()
    Kubectl kubectl = new Kubectl()
    Conditionals conditionals = new Conditionals()

    pod.node(
        containers: [ kubectl.containerTemplate(), vault.containerTemplate() ],
        volumes: [ ssh.keysVolume() ]
    ) {
        stage('Checkout') {
            gitScm.checkout(repo)
        }

        stage('Generate Secrets') {
            conditionals.when(secrets != null && secrets.size() > 0) {
                def processedSecrets

                vault.container {
                    processedSecrets = vault.processTemplates(repo, environment, secrets)
                }

                kubectl.container {
                    kubectl.createSecret(repo, environment, processedSecrets)
                }
            }
        }

        stage('Deploy') {
            kubectl.deploy(environment, repo, image.tag)
        }
    }

}

return this
