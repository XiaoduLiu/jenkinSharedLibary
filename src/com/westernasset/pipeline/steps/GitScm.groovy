package com.westernasset.pipeline.steps

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.westernasset.pipeline.models.GitRepository;

def checkout() {
    def gitRemoteURLPattern = Pattern.compile('https?://github\\.westernasset\\.com/(?<user>[\\w._-]+)/(?<repo>[\\w._-]+)\\.git/?')
    GitRepository git

    deleteDir()
    checkout(scm)

    String gitCommit = sh(label: 'Get Git commit', returnStdout: true, script: "git log -n 1 --pretty=format:'%h'")
    git = new GitRepository(scm.userRemoteConfigs[0].getUrl(), gitCommit, env.BRANCH_NAME)

    return git
}

def checkout(GitRepository repo) {
    deleteDir()
    git url: repo.scm, branch: repo.branch
    sh "git reset --hard ${repo.commit}"
}

def checkout(GitRepository repo, String credentialsId) {
    deleteDir()
    git url: repo.scm, branch: repo.branch, credentialsId: credentialsId
    sh "git reset --hard ${repo.commit}"
}

return this
