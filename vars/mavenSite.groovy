#!/usr/bin/groovy

import com.westernasset.pipeline.models.*
import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.builds.*

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def conditionals = new Conditionals()
    def mavenBuild = new MavenBuild()

    conditionals.lockWithLabel {
    
        println config.builderTag
        println config.scm
        println config.branch

        mavenBuild.mavenSiteDeploy(config.builderTag, config.scm, config.branch)
    }

}
