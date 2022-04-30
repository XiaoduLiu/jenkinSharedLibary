package com.westernasset.pipeline.models

import java.util.regex.Matcher
import java.util.regex.Pattern

import com.cloudbees.groovy.cps.NonCPS

import com.westernasset.pipeline.models.ProdDockerImages

class NonprodDockerImage implements DockerImage, Serializable {

    private GitRepository repo
    private String appBaseDir
    private String appVersion
    private String imageRepoUri
    private String nonprodKey
    private String buildNumber
    private String additionalTagPart

    NonprodDockerImage(GitRepository repo, def env, String buildNumber) {
        this.repo = repo
        this.buildNumber = buildNumber
        this.appBaseDir = null
        this.appVersion = null
        this.additionalTagPart = null

        imageRepoUri = env.IMAGE_REPO_URI
        nonprodKey = env.IMAGE_REPO_NONPROD_KEY
    }

    NonprodDockerImage(GitRepository repo, def env, String buildNumber, String appBaseDir, String appVersion) {
        this.repo = repo
        this.buildNumber = buildNumber
        this.appBaseDir = appBaseDir
        this.appVersion = appVersion
        this.additionalTagPart = null

        imageRepoUri = env.IMAGE_REPO_URI
        nonprodKey = env.IMAGE_REPO_NONPROD_KEY
    }

    NonprodDockerImage(GitRepository repo, def env, String buildNumber, String appBaseDir, String appVersion, String additionalTagPart) {
        this.repo = repo
        this.buildNumber = buildNumber
        this.appBaseDir = appBaseDir
        this.appVersion = appVersion
        this.additionalTagPart = additionalTagPart

        imageRepoUri = env.IMAGE_REPO_URI
        nonprodKey = env.IMAGE_REPO_NONPROD_KEY
    }

    @NonCPS
    @Override
    String getTag() {
        def version = (this.appVersion!=null)?this.appVersion:repo.commit
        return (this.appBaseDir!=null)? repo.branch + '-' + this.appBaseDir + "-" + version + '-' + buildNumber : repo.branch + '-' + version + '-' + buildNumber
    }

    @NonCPS
    String getTagWithAdditionalTagPart() {
        def version = (this.appVersion!=null)?this.appVersion:repo.commit
        return (this.additionalTagPart!=null)? repo.branch + '-' + this.additionalTagPart + "-" + version + '-' + buildNumber : repo.branch + '-' + version + '-' + buildNumber
    }

    @NonCPS
    String getImageName() {
        return repo.organization + '/' + repo.safeName
    }

    @NonCPS
    String getShortImage() {
        return imageName + ':' + tag
    }

    @Override
    String getImage() {
        return imageRepoUri + '/' + nonprodKey + '/' + shortImage
    }

    String getImageWithAdditionalTagPart() {
        return imageRepoUri + '/' + nonprodKey + '/' + repo.organization + '/' + repo.safeName + ':' + getTagWithAdditionalTagPart()
    }

}
