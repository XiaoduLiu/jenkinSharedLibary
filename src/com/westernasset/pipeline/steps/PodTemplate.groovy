package com.westernasset.pipeline.steps

def node(Map args, Closure body) {
    List containers = args.containers ?: []
    List volumes = args.volumes ?: []
    String namespace = args.namespace ?: 'devops-jenkins'
    String cloud = args.cloud ?: 'pas-development'

    podTemplate(
        cloud: cloud,
        serviceAccount: 'jenkins',
        namespace: 'devops-jenkins',
        containers: containers,
        volumes: volumes) {

        node(POD_LABEL) {
            return body()
        }
    }
}

def awsNode(Map args, Closure body) {
    List containers = args.containers ?: []
    List volumes = args.volumes ?: []
    String namespace = args.namespace ?: 'devops-jenkins'
    String cloud = args.cloud ?: 'pas-development'

    podTemplate(
        cloud: cloud,
        serviceAccount: 'jenkins',
        namespace: 'devops-jenkins',
        containers: containers,
        nodeSelector: 'node-role.westernasset.com/builder=true',
        volumes: volumes) {

        node(POD_LABEL) {
            return body()
        }
    }
}

return this
