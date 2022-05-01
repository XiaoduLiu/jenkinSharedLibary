package com.aristotlecap.pipeline.models

import com.lesfurets.jenkins.unit.BasePipelineTest
import com.aristotlecap.pipeline.models.GitRepository
import org.junit.*
import org.yaml.snakeyaml.Yaml

import static com.lesfurets.jenkins.unit.MethodCall.*
import static groovy.test.GroovyAssert.*

class JenkinsfileValidationTest {

    private Map config = [
        'hello': 'world',
        'foo': 'bar',
        'templates': [
            'a.ctmpl',
            'b.ctmpl',
        ],
        'secrets': [
            'a',
            'b',
            'c',
        ],
        'buildTemplates': [
            'a.template',
            'b.template',
        ],
        'buildSecrets': [
            'a',
            'b',
        ]
    ]

    @Test
    void testRequiredField() {
        def validation = new JenkinsfileValidation('hello')
        validation.require('foo')

        def errors = validation.validate(config)

        assert errors.size() == 0
    }

    @Test
    void testMissingRequiredField() {
        def validation = new JenkinsfileValidation('hello', 'missing')
        validation.require('nothing')

        def errors = validation.validate(config)

        assert errors.contains("Missing required field 'missing'")
        assert errors.contains("Missing required field 'nothing'")
        assert errors.size() == 2
    }

    @Test
    void testRequiredType() {
        def validation = new JenkinsfileValidation()
        validation.requireType('hello', String.class)
        validation.requireType('foo', Integer.class)

        def errors = validation.validate(config)

        assert errors.contains("Invalid type for field 'foo' expected: class java.lang.Integer")
        assert errors.size() == 1
    }

    @Test
    void testListMismatch() {
        def validation = new JenkinsfileValidation()
        validation.matchLists('templates', 'secrets', 'missing', 'hello')
        validation.matchLists('buildTemplates', 'buildSecrets')

        def errors = validation.validate(config)

        assert errors.contains("Mismatched lengths between fields: templates, secrets, missing, hello")
        assert errors.contains("Invalid type for field 'hello' expected: interface java.util.List")
        assert errors.size() == 2
    }

    @Test
    void testListMismatchDockerBatch() {
        def validation = new JenkinsfileValidation()
        validation.matchLists('buildTimeTemplates', 'buildTimeSecrets')
        validation.matchLists('templates', 'secrets')

        def errors = validation.validate([
            'templates': ['conf/secrets.ctmpl'],
            'secrets': ['secrets']
        ])

        assert errors.size() == 0
    }

}
