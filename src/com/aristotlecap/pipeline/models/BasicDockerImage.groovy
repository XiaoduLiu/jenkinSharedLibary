package com.aristotlecap.pipeline.models

import com.cloudbees.groovy.cps.NonCPS

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class BasicDockerImage implements DockerImage {

    static BasicDockerImage fromJsonString(String text) {
        JsonSlurper parser = new JsonSlurper()
        Map json = parser.parseText(text)
        Validation validation = new Validation('name', 'tag')
        List<String> errors = validation.check(json)
        if (errors.size() > 0) {
            throw new IllegalArgumentException(errors.join('\n'))
        }
        return new BasicDockerImage(json.name, json.tag)
    }

    static BasicDockerImage fromImage(String image) {
        if (image.contains(':')) {
            List components = image.split(':')
            if (components.length != 2) {
                throw new IllegalArgumentException('Invalid builder image syntax')
            }
            return new BasicDockerImage(components[0], components[1])
        }
        return new BasicDockerImage(image, 'latest')
    }

    protected final String name
    protected final String tag

    BasicDockerImage(String name, String tag) {
        this.name = name
        this.tag = tag
    }

    @Override
    String getImage() {
        return name + ':' + tag
    }

    @Override
    String getTag() {
        return tag
    }

    @NonCPS
    String toJsonString() {
        return JsonOutput.toJson([
            name: this.name,
            tag: this.tag,
        ])
    }

}
