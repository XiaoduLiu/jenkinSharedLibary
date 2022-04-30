package com.westernasset.pipeline.steps

import com.westernasset.pipeline.models.*

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
