#!/usr/bin/groovy

import java.lang.String

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    node("agent") {
      try {
	        stage ('Start_Kafka_Nodes') {
	        	sh "echo Start_Kafka_Nodes"
	        	sh 'ssh memsql@uswest2xcontrol100 "/opt/dbadmin/memsql/scripts/aws_environment_control.bash 3 start kafkadev"'
  	        }
          stage ('Wait_for_Kafkas') {
              sh "echo Wait_for_Kafkas; sleep 5;"
            }
	        stage ('Restart_Kafkas') {
	            sh "echo Restart_Kafkas"
	            sh 'ssh memsql@uswest2xcontrol100 "mussh -l memsql -H ~/.mussh_hosts/kafkadevs -m0 -c \'date; sudo systemctl stop kafka; sudo systemctl stop zookeeper; sleep 3; sudo systemctl start zookeeper; sleep 5; sudo systemctl start kafka; date;\'; "'
            }
          stage ('Status_Kafkas') {
	            sh "echo Status_Kafkas"
              sh 'ssh memsql@uswest2xcontrol100 "mussh -l memsql -H ~/.mussh_hosts/kafkadevs -m0 -c \'date; ps -ef|egrep zookeeper; ps -ef|egrep kafka; date;\';" '
            }
	        stage ('Start_Memsql_Nodes') {
	        	  sh "echo Start_Memsql_Nodes"
	            sh 'ssh memsql@uswest2xcontrol100 "/opt/dbadmin/memsql/scripts/aws_environment_control.bash 3 start memsqlTest3"'
  	        }
          stage ('Restart_Memsqls') {
	            sh "echo Restart_Memsqls"
              sh "echo Waiting for initial Memsql start; sleep 20;"
              sh 'ssh memsql@uswest2xcontrol100 "date; ssh-add -l; /opt/dbadmin/memsql/scripts/bounce_tier_apps.bash stop dev memsql"'
              sh 'ssh memsql@uswest2xcontrol100 "date; ssh-add -l; /opt/dbadmin/memsql/scripts/bounce_tier_apps.bash status dev memsql"'
              sh 'ssh memsql@uswest2xcontrol100 "date; ssh-add -l; /opt/dbadmin/memsql/scripts/bounce_tier_apps.bash start dev memsql"'
              sh 'ssh memsql@uswest2xcontrol100 "date; ssh-add -l; /opt/dbadmin/memsql/scripts/bounce_tier_apps.bash status dev memsql"'
            }
          stage ('Start_PythonApp_Nodes') {
            sh "echo Start_PythonApp_Nodes"
            sh 'ssh memsql@uswest2xcontrol100 "/opt/dbadmin/memsql/scripts/aws_environment_control.bash 3 start imrsdev"'
    	    }
      } catch (err) {
	        currentBuild.result = 'FAILED'
	        throw err
	    }
   }
}
