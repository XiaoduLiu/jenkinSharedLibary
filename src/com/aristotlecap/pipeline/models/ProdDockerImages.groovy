package com.aristotlecap.pipeline.models

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.cloudbees.groovy.cps.NonCPS

class ProdDockerImages implements Serializable {

    private String imageRepoUri
    private String nonprodKey
    private String prodKey
    private String imageName
    private String releaseVersion
    private String qaEnvironment

    ProdDockerImages(String imageRepoUri, String nonprodKey, String prodKey, String imageName, String releaseVersion, String qaEnvironment) {
        this.imageRepoUri = imageRepoUri
        this.nonprodKey = nonprodKey
        this.prodKey = prodKey
        this.imageName = imageName
        this.releaseVersion = releaseVersion
        this.qaEnvironment = qaEnvironment
    }

    ProdDockerImages(GitRepository repo, def env, String releaseVersion, String qaEnvironment) {
        imageRepoUri = env.IMAGE_REPO_URI
        nonprodKey = env.IMAGE_REPO_NONPROD_KEY
        prodKey = env.IMAGE_REPO_PROD_KEY
        imageName = repo.organization + '/' + repo.safeName
        this.releaseVersion = releaseVersion
        this.qaEnvironment = qaEnvironment
    }

    @NonCPS
    String getCrTag() {
        return releaseVersion + '-' + qaEnvironment
    }

    @NonCPS
    String getApproveImage() {
        return imageRepoUri + '/' + nonprodKey + '/' + imageName + ':' + crTag
    }

    @NonCPS
    String getReleaseImage() {
        return imageRepoUri + '/' + prodKey + '/' + imageName + ':' + releaseVersion
    }

    @NonCPS
    String getReleaseCrImage() {
        return imageRepoUri + '/' + prodKey + '/' + imageName + ':' + crTag
    }

}
