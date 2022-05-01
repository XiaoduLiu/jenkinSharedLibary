import java.lang.String

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def lockLabel = "${env.lockLabel}"
  echo lockLabel

  def org = params.organizationName
  def repo = params.repoName
  def serverList = params.serverList
  def appUser = params.appUser
  def serverEnv = params.env

  echo org
  echo repo
  echo serverList
  echo appUser
  echo serverEnv

  def templatesArray = config.templates
  def templatesString = "null"
  if (templatesArray != null && templatesArray.size() > 0) {
     templatesString = templatesArray.join("\n")
  }

  def secretsArray = config.secrets
  def secretsString = "null"
  if (secretsArray != null && secretsArray.size() > 0) {
     secretsString = secretsArray.join("\n")
  }

  def build = new com.aristotlecap.pipeline.onboard.ssh_commons()

  if (lockLabel != 'null') {
    lock(label: "${lockLabel}")  {
      build.sshKeysDistribution(
        org,
        repo,
        serverList,
        appUser,
        templatesString,
        secretsString,
        serverEnv
      )
    }
  } else {
    build.sshKeysDistribution(
      org,
      repo,
      serverList,
      appUser,
      templatesString,
      secretsString,
      serverEnv
    )
  }
}
