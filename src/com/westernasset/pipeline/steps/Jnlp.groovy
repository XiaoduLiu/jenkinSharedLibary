package com.westernasset.pipeline.steps

def container(Closure body) {
    container('jnlp') {
        return body()
    }
}

def containerTemplate(String image = env.TOOL_AGENT) {
    return containerTemplate(name: 'jnlp', image: image, args: '${computer.jnlpmac} ${computer.name}')
}

return this
