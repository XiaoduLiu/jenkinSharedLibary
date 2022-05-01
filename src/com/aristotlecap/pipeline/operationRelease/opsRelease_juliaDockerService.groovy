package com.aristotlecap.pipeline.operationRelease

import com.aristotlecap.pipeline.Commons
import com.aristotlecap.pipeline.models.BasicDockerImage
import com.aristotlecap.pipeline.models.GitRepository
import com.aristotlecap.pipeline.models.Validation
import com.aristotlecap.pipeline.steps.Prompt
import com.aristotlecap.pipeline.builds.KubernetesBuild

void validate(Map args) {
    Validation validation = new Validation(
        'config', 'repo', 'projectType', 'project', 'releaseImage', 'changeRequest', 'buildNumber'
    )
    List errors = validation.check(args)
    if (errors.size() > 0) {
        error errors.join('\n')
    }
}

void release(Map args) {
    validate(args)
    GitRepository repo = GitRepository.fromJsonString(args.repo)
    Map config = readJSON(text: args.config)
    Map project = readJSON(text: args.project)
    BasicDockerImage releaseImage = BasicDockerImage.fromJsonString(args.releaseImage)
    currentBuild.displayName = "${repo.branch}-${project.version}-${args.buildNumber}-${args.changeRequest}"
    Prompt prompt = new Prompt()
    Commons commons = new Commons()

    if (!prompt.approve(message: 'Approve Release?')) {
        return // Do not proceed if there is no approval
    }
    stage('Production Deployment') {
        KubernetesBuild kubernetesBuild = new KubernetesBuild()
        kubernetesBuild.prodBuild(
            repo,
            'prod',
            commons.getProdCluster(config.prodEnv),
            releaseImage.tag,
            config.secrets ?: [:]
        )
    }
}
