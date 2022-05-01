package com.aristotlecap.pipeline.steps

import com.aristotlecap.pipeline.models.*

def container(Closure body) {
    container('tableau') {
        return body()
    }
}

def containerTemplate(BuilderImage image) {
    return containerTemplate(image.image)
}

def containerTemplate(String image) {
    return containerTemplate(name: 'tableau', image: image, ttyEnabled: true)
}
