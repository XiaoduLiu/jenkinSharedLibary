package com.westernasset.pipeline.builds

import com.westernasset.pipeline.steps.*
import com.westernasset.pipeline.models.*

void pushReleaseTag(GitRepository repo, String releaseVersion, String changeRequest) {
    PodTemplate pod = new PodTemplate()
    GitScm gitScm = new GitScm()
    Git gitStep = new Git()
    Jnlp jnlp = new Jnlp()
    Ssh ssh = new Ssh()

    pod.node(
        containers: [ jnlp.containerTemplate() ],
        volumes: [ ssh.keysVolume() ]
    ) {
        jnlp.container {
            gitScm.checkout(repo, 'ghe-jenkins')
            gitStep.useJenkinsUser()
            String tag = "${repo.name}-${releaseVersion}"
            sh """
                git tag -a ${tag} -m 'Release for ${changeRequest}'
                git push origin ${tag}
            """
        }
    }
}

return this
