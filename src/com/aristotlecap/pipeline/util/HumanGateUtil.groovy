package com.aristotlecap.pipeline.util;

def gate(choicesOpt=null,
         releaseOpt=false,
         qaOpt=false,
         destroyOpt=false,
         displayLabel,
         stageName="Ready for Non-Prod Deploy?",
         msg="Approve Non-Prod Deploy?",
         releaseMsg='Ready for Maven Release?') {

  //output parameters
  def releaseFlag = false
  def abortedOrTimeoutFlag = false
  def deployEnv
  def crNumber
  def destroyFlag = false
  //temp parameter
  def userInput
  //gate stage (can be nonprod deploy or qa approve human gate)
    currentBuild.displayName = displayLabel
    try {
      def params = []
      if (releaseOpt) {
        params.add([$class: 'BooleanParameterDefinition', defaultValue: false, description: releaseMsg, name: 'releaseFlag'])
      }
      if (destroyOpt) {
        params.add([$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Ready for Destroy or Approve Action?', name: 'destroyFlag'])
      }
      if (choicesOpt != null) {
        params.add([$class: 'ChoiceParameterDefinition', choices: choicesOpt, description: 'Environments', name: 'env'])
      }
      if (qaOpt) {
        params.add([$class: 'TextParameterDefinition', defaultValue: '', description: 'CR Number', name: 'crNumber'])
      }
      stage(stageName) {
        checkpoint stageName
        try {
          timeout(time: 60, unit: 'SECONDS') {
            userInput = input(id: 'Proceed1', message: msg, parameters: params)
          }
          deployEnv = choicesOpt!=null?(params.size()>1?userInput['env']:userInput):null
          println deployEnv

          releaseFlag = releaseOpt?(params.size()>1?userInput['releaseFlag']:userInput):false
          println releaseFlag

          crNumber = qaOpt?(params.size()>1?userInput['crNumber']:userInput):null
          println crNumber

          destroyFlag = destroyOpt?(params.size()>1?userInput['destroyFlag']:userInput):false
          println destroyFlag

          currentBuild.result = 'SUCCESS'
          echo "RESULT1: ${currentBuild.result}"
        } catch(ex) {
          echo "RESULT2: ${currentBuild.result}"
          currentBuild.result = 'SUCCESS'
        }
      }
      return [
        abortedOrTimeoutFlag: abortedOrTimeoutFlag,
        releaseFlag: releaseFlag,
        deployEnv: deployEnv,
        crNumber: crNumber,
        destroyFlag: destroyFlag
      ]
    } catch(err) { // timeout reached or input false
      print err.getMessage()
      currentBuild.result = 'SUCCESS'
      try {
        def user = err.getCauses()[0].getUser()
        if('SYSTEM' == user.toString()) { // SYSTEM means timeout.
          didTimeout = true
          println "timeout ...."
          currentBuild.result = 'SUCCESS'
        } else {
          didAbort = true
          print "Aborted by: [${user}]"
          currentBuild.result = 'SUCCESS'
        }
      } catch(e) {
        println e.getMessage()
      }
      echo "RESULT: ${currentBuild.result}"
      return [
        abortedOrTimeoutFlag: true,
        releaseFlag: false,
        deployEnv: null,
        crNumber: null,
        destroyFlag: false
      ]
    }
}
