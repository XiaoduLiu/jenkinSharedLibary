package com.aristotlecap.pipeline.steps

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def when(boolean condition, body) {
    def config = [:]
    body.resolveStrategy = Closure.OWNER_FIRST
    body.delegate = config

    if (condition) {
        body()
    } else {
        Utils.markStageSkippedForConditional(STAGE_NAME)
    }
}

def lockWithLabel(Closure body) {
    lockWithLabel(env.lockLabel, body)
}

def lockWithLabel(String lockLabel, Closure body) {
    if (lockLabel) {
        lock(label: lockLabel) {
            return body()
        }
    } else {
        return body()
    }
}

return this
