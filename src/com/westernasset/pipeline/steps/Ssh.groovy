package com.westernasset.pipeline.steps

import com.westernasset.pipeline.models.Validation

def keysVolume() {
    return persistentVolumeClaim(claimName: 'jenkins-agent-ssh-nonprod', mountPath: '/home/jenkins/.ssh')
}

def prodKeysVolume() {
    return persistentVolumeClaim(claimName: 'jenkins-agent-ssh-prod', mountPath: '/home/jenkins/.ssh')
}

def rm(Map args) {
    String key = args.key ?: "./id_rsa_scriptserver"
    sh "ssh -i ${key} ${args.host} 'rm -r ${args.dir}'"
}

def sh(Map args) {
    def key = args.key ?: '/home/jenkins/.ssh/id_rsa'
    def script = args.script ?: ''
    sh "ssh-agent sh -c 'ssh-add ${key}; ${script}'"
}

def scp(Map args) {
    def validation = new Validation('host', 'src', 'dest')
    List errors = validation.requireType('key', String).check(args)
    if (errors.size() > 0) {
        error Validation.format(errors)
    }
    String key = args.key ?: "./id_rsa_scriptserver"
    String destDir = args.dest[0..args.dest.lastIndexOf('/')]
    sh "ssh -i ${key} ${args.host} 'mkdir -p ${destDir}'"
    sh "scp -i ${key} ${args.src} ${args.host}:${args.dest}"
}

return this
