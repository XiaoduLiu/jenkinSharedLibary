package com.westernasset.pipeline.steps

import com.westernasset.pipeline.util.TomlReader
import com.westernasset.pipeline.models.Validation

void container(Closure body) {
    container('julia') {
        body()
    }
}

def containerTemplate(String image) {
    return containerTemplate(name: 'julia', image: image, ttyEnabled: true)
}

void addRegistries(List<String> registries) {
    Julia julia = new Julia()
    registries.each { registry ->
        if (registry == 'General') {
            julia.eval expr: "using Pkg; Pkg.Registry.add(\"${registry}\")"
        } else if (registry.startsWith('https://') || registry.startsWith('git@')) {
            julia.eval expr: "using Pkg; Pkg.Registry.add(RegistrySpec(url = \"${registry}\"))"
        } else {
            error "Unrecognized Julia registry '${registry}'"
        }
    }
}

void eval(String expr) {
    eval expr: expr
}

void eval(Map param) {
    Validation validation = new Validation('expr')
    List errors = validation
        .requireType('project', CharSequence)
        .check(param)
    if (errors.size() > 0) {
        error errors.join('\n')
    }

    String projectArg = param.project ? "--project=${param.project}" : ''
    String expr = param.expr.replace("'", "\\'")

    sh "julia --color=no ${projectArg} -e '${expr}'"
}

void exec(param) {
    Validation validation = new Validation('programfile')
    List errors = validation
        .requireType('project', CharSequence)
        .requireType('args', List)
        .check(param)
    if (errors.size() > 0) {
        error errors.join('\n')
    }

    String projectArg = param.project ? "--project=${param.project}" : ''

    String args
    if (param.args instanceof String) {
        args = param.args
    } else if (param.args instanceof List) {
        args = param.args.join(' ')
    } else if (!param.args) {
        args = ''
    }

    sh "julia --color=no ${projectArg} -- ${param.programfile} ${args}"
}

Map project(Map args = [file: 'Project.toml']) {
    TomlReader toml = new TomlReader()
    return toml.read(readFile(file: args.file))
}

Boolean canRunTest(String file = 'test/runtests.jl') {
    container {
        return fileExists(file: file)
    }
}

void withDocumenterKey(String credentialsId, Closure body) {
    withCredentials(bindings: [sshUserPrivateKey(
        credentialsId: credentialsId,
        keyFileVariable: 'DOCUMENTER_KEY_FILE'
    )]) {
        String documenterKey = sh(returnStdout: true, script: "cat ${DOCUMENTER_KEY_FILE} | base64").trim()
        withEnv(["DOCUMENTER_KEY=${documenterKey}"]) {
            body()
        }
    }
}

Boolean canBuildDocumentation(String file = 'docs/make.jl') {
    container {
        Boolean hasGit = sh(returnStatus: true, script: 'which git') == 0
        return hasGit && fileExists(file: file)
    }
}

void runDocumenter(Map args) {
    String path = args.path ?: 'docs'
    String makefile = args.makefile ?: "${path}/make.jl"
    String key = args.key ?: 'ghe-jenkins'
    eval project: path, expr: 'using Pkg; Pkg.develop(PackageSpec(path=pwd())); Pkg.instantiate()'
    withDocumenterKey(key) {
        exec project: path, programfile: makefile
    }
}

return this
