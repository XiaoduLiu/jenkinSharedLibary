package com.westernasset.pipeline.models

import com.westernasset.pipeline.models.*

import java.util.regex.Matcher
import java.util.regex.Pattern

import com.cloudbees.groovy.cps.NonCPS

class KubernetesProductionDeployment extends ProductionDeployment implements Serializable {

    final DockerImage image
    final Map templates

    KubernetesProductionDeployment(String projectType, GitRepository repo, String crNumber, String prodEnv, String drEnv, String releaseVersion, DockerImage image, Map templates) {
        super(projectType, repo, crNumber, prodEnv, drEnv, releaseVersion)
        this.image = image
        this.templates = templates
    }

}
