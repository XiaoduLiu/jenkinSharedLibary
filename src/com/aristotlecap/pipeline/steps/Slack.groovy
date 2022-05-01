package com.aristotlecap.pipeline.steps

import com.aristotlecap.pipeline.models.*

def sendSlackMessage(applicationName, typeOfApp, branchName, buildNumber, userId, commit, deployEnv, errorMassage) {
  def message = (errorMassage != null)? "Failed to deploy ${typeOfApp} app ${applicationName} for ${deployEnv} from ${branchName} branch with Sha ${commit} at build life ${buildNumber} by ${userId} with error : ${errorMassage}":"Successful deploy ${typeOfApp} app ${applicationName} for ${deployEnv} from ${branchName} branch with Sha ${commit} at build life ${buildNumber} by ${userId}"
  def attachments = [
    [
      text: "${message}",
      fallback: "${message}",
      color: '#3f6b3b'
    ]
  ]
  slackSend(channel: "#it-im-deployments", attachments: attachments)
}
