#!/usr/bin/groovy

import java.lang.String

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    node("agent") {
      try {
          stage ('Stop_PythonApp_Nodes') {
            sh "echo Stop_PythonApp_Nodes"
            sh "ssh memsql@uswest2xcontrol100 '/opt/dbadmin/memsql/scripts/aws_environment_control.bash 3 stop imrsdev'"
          }
          stage ('Stop_Memsql_Nodes') {
            sh "echo Stop_Memsql_Nodes"
            sh "ssh memsql@uswest2xcontrol100 '/opt/dbadmin/memsql/scripts/aws_environment_control.bash 3 stop memsqlTest3'"
          }
          stage ('Stop_Kafka_Nodes') {
	        	sh "echo Stop_Kafka_Nodes"
	        	sh "ssh memsql@uswest2xcontrol100 '/opt/dbadmin/memsql/scripts/aws_environment_control.bash 3 stop kafkadev'"
	        }
	    } catch (err) {
	        currentBuild.result = 'FAILED'
	        throw err
	    }
   }
}
