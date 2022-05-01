package com.aristotlecap.pipeline;

import com.aristotlecap.pipeline.steps.Hadolint

def build(projectType, gitBranchName, buildNumber, builderTag, nonProdEnvironments,
           qaEnvs, prodEnv, drEnv, releaseVersion, templates,
           secrets, buildSteps, buildTimeTemplates, buildTimeSecrets, mixCaseRepo,
           testEnv) {

  def commons = new com.aristotlecap.pipeline.Commons()
  def repo

  def builderImage = "${env.TOOL_BUSYBOX}"
  if (builderTag != 'null') {
    builderImage = "${env.IMAGE_REPO_URI}/${env.IMAGE_REPO_PROD_KEY}/${env.IMAGE_BUIDER_REPO}:${builderTag}"
  }

  def hadolint = new Hadolint()

  podTemplate(
      cloud: 'pas-development',
      serviceAccount: 'jenkins',
      namespace: 'devops-jenkins',
      containers: [
        containerTemplate(name: 'builder', image: builderImage, ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'docker', image: env.TOOL_DOCKER, ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'kubectl', image: env.TOOL_KUBECTL, ttyEnabled: true),
        containerTemplate(name: 'vault', image: env.TOOL_VAULT, ttyEnabled: true),
        containerTemplate(name: 'sonar', image: env.TOOL_SONAR_SCANNER, ttyEnabled: true, command: 'cat'),
        hadolint.containerTemplate()
      ],
      volumes: [
        persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2'),
        persistentVolumeClaim(claimName: "jenkins-agent-ssh-nonprod", mountPath: '/home/jenkins/.ssh'),
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
    ]) {

      node(POD_LABEL) {
        try {
          repo = commons.clone(mixCaseRepo)

          imageTag = commons.setJobLabelNonJavaProject(gitBranchName, repo.gitCommit, buildNumber, releaseVersion)

          if (testEnv != 'null') {
            commons.secretProcess(templates, secrets, testEnv, repo.organizationName, repo.appGitRepoName, false)
          }

          commons.localBuildSteps("Build & Test", buildSteps)
          commons.sonarProcess(gitBranchName)

          if(buildTimeTemplates != 'null') {
            commons.secretProcess(buildTimeTemplates, buildTimeSecrets, 'all', repo.organizationName, repo.appGitRepoName, false)
          }

          if (fileExists("${workspace}/Dockerfile")) {
            echo 'Yes, there is a Dockerfile exist at the project root and there is a docker build'
            hadolint.lint()
            commons.dockerBuild(env.IMAGE_REPO_URI, env.IMAGE_REPO_NONPROD_KEY, repo.appDtrRepo, imageTag, repo.organizationName, repo.appGitRepoName, gitBranchName, repo.gitCommit, 'null', 'Docker Build', 'pas-development')
          } else {
            echo 'No, there is no DockerFile at the project root and there is no docker build'
          }

          if(buildTimeTemplates != 'null') {
            commons.deleteSecretFiles(buildTimeSecrets)
          }

        } catch (err) {
          currentBuild.result = 'FAILED'
          throw err
        }
      }
  }

  def gateutil = new com.aristotlecap.pipeline.util.HumanGateUtil()
  def gate = gateutil.gate(nonProdEnvironments, false, false, false, currentBuild.displayName, "Should I deploy to Non-Prod?", "Approve Non-Prod Deploy?", "Ready for Release")
  if (gate.deployEnv != null) {
    print "Deploy to nonprod ENV"
    print nonProdEnvironments
    def (deployEnv, clusterName) = commons.getNonProdCluster(nonProdEnvironments, gate.deployEnv)
    println(deployEnv)
    println(clusterName)

    commons.nonprodDeployment(deployEnv, clusterName, repo, gitBranchName, templates, secrets, imageTag, false, false)

    def qaApprove = new com.aristotlecap.pipeline.qa.qaApprove()
    qaApprove.approve(repo.gitScm, gitBranchName, repo.gitCommit, buildNumber, repo.appDtrRepo,
                      imageTag, repo.organizationName, repo.appGitRepoName, prodEnv, drEnv,
                      'null', 'null', projectType, templates, secrets,
                      releaseVersion, 'null', imageTag, 'null')

  }

}
