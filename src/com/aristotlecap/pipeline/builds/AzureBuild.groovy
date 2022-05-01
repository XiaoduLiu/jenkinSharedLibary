package com.aristotlecap.pipeline.builds

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.build.*


def build(config) {
    def repo
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def jnlp = new Jnlp()
    def terragrunt = new Terragrunt()
    def terrafrom = new Terrafrom()
    def git = new Git()
    def ssh = new Ssh()
    def display = new DisplayLabel()
    print config
    pod.node(
      containers: [
        jnlp.containerTemplate(),
        terrafrom.containerTemplate(),
        terragrunt.containerTemplate()],
      volumes: [
        ssh.keysVolume()]
      ) {
        stage('Clone') {
          repo = gitScm.checkout()
        }
        stage("Terraform Plan") {
          yaml = readYaml file: "jenkins.yaml"
          for (component in yaml.components) {
            print component
            if (component.tool == "terraform") {
              terrafrom.azurePlan(component)
            } else {
              terragrunt.azurePlan(component)
            }
          }
        }
      }
      return [repo]
}

def deploy(config) {
    def repo
    def pod = new PodTemplate()
    def gitScm = new GitScm()
    def jnlp = new Jnlp()
    def terragrunt = new Terragrunt()
    def terrafrom = new Terrafrom()
    def git = new Git()
    def ssh = new Ssh()
    def display = new DisplayLabel()
    print config
    pod.node(
      containers: [
        jnlp.containerTemplate(),
        terrafrom.containerTemplate(),
        terragrunt.containerTemplate()],
      volumes: [
        ssh.keysVolume()]
      ) {
        repo = gitScm.checkout()
        stage("Terraform Apply") {
          yaml = readYaml file: "jenkins.yaml"
          for (component in yaml.components) {
            print component
            if (component.tool == "terraform") {
              terrafrom.azureApply(component)
            } else {
              terragrunt.azureApply(component)
            }
          }
        }
      }
      return [repo]
}
