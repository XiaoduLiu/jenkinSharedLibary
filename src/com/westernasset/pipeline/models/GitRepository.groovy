package com.westernasset.pipeline.models

import com.cloudbees.groovy.cps.NonCPS

import com.westernasset.pipeline.models.Validation
import com.westernasset.pipeline.models.*

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class GitRepository implements Serializable {

    static GitRepository fromJsonString(String text) {
        JsonSlurper parser = new JsonSlurper()
        Map json = parser.parseText(text)
        Validation validation = new Validation(
            'name', 'organization', 'scm', 'commit', 'branch', 'dtr'
        )
        List<String> errors = validation.check(json)
        if (errors.size() > 0) {
            throw new IllegalArgumentException(errors.join('\n'))
        }
        return new GitRepository(json.name, json.organization, json.scm, json.commit, json.branch, json.dtr)
    }

    final String name;
    final String organization;
    final String scm;
    final String commit;
    final String branch;
    final String dtr;

    GitRepository(String name, String organization, String scm, String commit, String branch, String dtr) {
        this.name = name
        this.organization = organization
        this.scm = scm
        this.commit = commit
        this.branch = branch
        this.dtr = dtr
    }

    GitRepository(String remoteURL, String gitCommit, String branch) {
        def gitRemoteURLPattern = Pattern.compile('https?://github\\.westernasset\\.com/(?<user>[\\w._-]+)/(?<repo>[\\w._-]+)\\.git/?')
        def matcher = (remoteURL =~ gitRemoteURLPattern)
        matcher.matches()

        name = matcher.group('repo')
        organization = matcher.group('user')
        scm = "git@github.westernasset.com:${organization}/${name}.git"
        dtr = clean("${organization}/${name}")

        commit = gitCommit.trim()
        this.branch = branch
    }

    @NonCPS
    String getSafeName() {
        return clean(name)
    }

    @NonCPS
    private String clean(def name) {
        return name.toString().replaceAll(/\./, '-').toLowerCase()
    }

    @NonCPS
    String toJsonString() {
        Map map = [:]
        map.name = name
        map.organization = organization
        map.scm = scm
        map.commit = commit
        map.branch = branch
        map.dtr = dtr
        return JsonOutput.toJson(map)
    }

}
