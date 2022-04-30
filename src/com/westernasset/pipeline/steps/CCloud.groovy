package com.westernasset.pipeline.steps

import com.westernasset.pipeline.models.*

def container(Closure body) {
  container('ccloud') {
    return body()
  }
}

def containerTemplate(String image) {
    return containerTemplate(name: 'ccloud', image: image, ttyEnabled: true)
}

def containerTemplate() {
  return containerTemplate(name: 'ccloud', image: "${env.TOOL_CCLOUD}", ttyEnabled: true, command: 'cat')
}

def ccloudLogin() {
  container {
    withCredentials([usernamePassword(credentialsId: "${env.CCLOUD_ADMIN}", usernameVariable: 'ccloud_email', passwordVariable: 'ccloud_password')]) {
      //echo sh(script: 'env|sort', returnStdout: true)
      sh """
        /usr/local/bin/ccloud_login.sh $ccloud_email '$ccloud_password'
      """
    }
  }
}

def uploadSchema(schemaPathes, environment) {
  container {
    for(path in schemaPathes) {
      print "processing path ->" + path
      def files = findFiles(glob: "${path}/*.avsc")
      for(file in files) {
        String filename = file.name
        println 'processing file -> ' + filename
        String prefix = (environment == 'prod')? '':environment+'.'
        String subject = prefix + filename.replaceAll('.avsc','')
        println 'subject or topic name -> ' + subject
        withCredentials([usernamePassword(credentialsId: "${env.SCHEMA_ADMIN}", usernameVariable: 'user', passwordVariable: 'password')]) {
          //echo sh(script: 'env|sort', returnStdout: true)
          sh """
            ccloud --version
            ccloud environment use $env.CCLOUD_ENV
            ccloud schema-registry schema create --subject $subject --schema ./$path/$filename --api-key $user --api-secret $password
          """
        }

      }
    }
  }
}

return this
