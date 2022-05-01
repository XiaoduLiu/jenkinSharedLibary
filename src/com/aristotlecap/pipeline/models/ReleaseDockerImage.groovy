package com.aristotlecap.pipeline.models

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.cloudbees.groovy.cps.NonCPS

class ReleaseDockerImage implements DockerImage, Serializable {

    private String imageRepoUri
    private String prodKey
    private String imageName
    private String releaseVersion

    ReleaseDockerImage(String imageRepoUri, String prodKey, String imageName, String releaseVersion, String qaEnvironment) {
        this.imageRepoUri = imageRepoUri
        this.prodKey = prodKey
        this.imageName = imageName
        this.releaseVersion = releaseVersion
    }

    ReleaseDockerImage(GitRepository repo, def env, String releaseVersion) {
        imageRepoUri = env.IMAGE_REPO_URI
        prodKey = env.IMAGE_REPO_PROD_KEY
        imageName = repo.organization + '/' + repo.safeName
        this.releaseVersion = releaseVersion
    }

    @NonCPS
    String getTag() {
        return releaseVersion
    }
    
    @NonCPS
    String getImage() {
        return imageRepoUri + '/' + prodKey + '/' + imageName + ':' + tag
    }

}
