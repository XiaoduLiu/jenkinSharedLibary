#!/usr/bin/groovy

import com.aristotlecap.pipeline.steps.*

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  print config

  def gitScm = new GitScm()

  node('atp') {

    stage("Clone") {
      gitScm.checkout()
    }
    stage('Testing') {
        String testList = config.tests.join("\n")
        uftScenarioLoad archiveTestResultsMode: 'DONT_ARCHIVE_TEST_REPORT', fsTimeout: '120', fsUftRunMode: 'Normal', testPaths: """${testList}"""
    }
  }

}
