package com.aristotlecap.pipeline.models

import com.aristotlecap.pipeline.models.*

import java.util.regex.Matcher
import java.util.regex.Pattern

import com.cloudbees.groovy.cps.NonCPS

class ProductionDeployment implements Serializable {
    
    final String projectType
    final GitRepository repo
    final String crNumber
    final String prodEnv
    final String drEnv
    final String releaseVersion
    final String branchName
    final String buildNumber

    ProductionDeployment(String projectType, GitRepository repo, String crNumber, String prodEnv, String drEnv, String releaseVersion) {
        this.projectType = projectType
        this.repo = repo
        this.crNumber = crNumber
        this.prodEnv = prodEnv
        this.drEnv = drEnv
        this.releaseVersion = releaseVersion
        this.branchName = ''
        this.buildNumber = ''
    }

    ProductionDeployment(String branchName, String buildNumber, String projectType, GitRepository repo, String crNumber, String prodEnv, String drEnv, String releaseVersion) {
        this.projectType = projectType
        this.repo = repo
        this.crNumber = crNumber
        this.prodEnv = prodEnv
        this.drEnv = drEnv
        this.releaseVersion = releaseVersion
        this.branchName = branchName
        this.buildNumber = buildNumber
    }

}
