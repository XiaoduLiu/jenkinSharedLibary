package com.aristotlecap.pipeline.steps

import com.aristotlecap.pipeline.models.*

def container(Closure body) {
    container('eksctl') {
        return body()
    }
}

def containerTemplate(BuilderImage image) {
    return containerTemplate(image.image)
}

def containerTemplate(String image) {
    return containerTemplate(name: 'eksctl', image: image, ttyEnabled: true)
}

def awsNonProdVolume() {
    return persistentVolumeClaim(claimName: 'jenkins-agent-aws-nonprod', mountPath: '/home/jenkins/.aws')
}

def awsProdVolume() {
    return persistentVolumeClaim(claimName: 'jenkins-agent-aws-prod', mountPath: '/home/jenkins/.aws')
}
