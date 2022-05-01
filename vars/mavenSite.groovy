#!/usr/bin/groovy

import com.aristotlecap.pipeline.models.*
import com.aristotlecap.pipeline.steps.*
import com.aristotlecap.pipeline.builds.*

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
