package com.aristotlecap.pipeline.models

import com.lesfurets.jenkins.unit.BasePipelineTest
import net.sf.json.JSONObject
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static groovy.test.GroovyAssert.*

class GitRepositoryTest {

    @Test
    void testGitRepository() {
        GitRepository repo = new GitRepository(
            'https://github.westernasset.com/risk/WAgg.jl.git',
            '7e7b415f7fae3e3092a617ab22a6f2bdf165648c',
            'master'
        )

        assert repo.name == 'WAgg.jl'
        assert repo.organization == 'risk'
        assert repo.scm == 'git@github.westernasset.com:risk/WAgg.jl.git'
        assert repo.safeName == 'wagg-jl'
        assert repo.commit == '7e7b415f7fae3e3092a617ab22a6f2bdf165648c'
        assert repo.branch == 'master'
    }

    @Test
    void testSerializeGitRepository() {
        def map = [
            'foo': 'bar',
            'hello': 'world'
        ]

        def string = JSONObject.fromObject(map).toString()
        println string

        def object = JSONObject.fromObject(string)
        println object
    }

}
