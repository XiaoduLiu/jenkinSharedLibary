package com.aristotlecap.pipeline.steps

def container(Closure body) {
    container('builder') {
        return body()
    }
}

def containerTemplate(String image) {
    return containerTemplate(name: 'builder', image: image, ttyEnabled: true)
}


return this
