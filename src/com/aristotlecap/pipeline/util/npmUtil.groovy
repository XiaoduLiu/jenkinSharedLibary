package com.aristotlecap.pipeline.util;

def setNpmrcFilelink() {
  sh """
    cp /home/jenkins/.npm/.npmrc /home/jenkins/.npmrc
    chmod +r /home/jenkins/.npmrc
    ls -la /home/jenkins
  """
}

def npmBuild(libraryType, angularLibs) {
  sh """
    npm install
  """
  if (libraryType.equalsIgnoreCase('cdk')) {
    println 'CDK module build'
    sh """
      npm run build
    """
  } else if (libraryType.equalsIgnoreCase('angular')) {
    for (lib in angularLibs) {
      println 'build:' + lib
      sh """
        ng build $lib --prod
      """
    }
  }
}

def npmRelease(libraryType, angularLibs) {
  if (libraryType.equalsIgnoreCase('cdk')) {
    releaseCdkModule()
  } else if (libraryType.equalsIgnoreCase('angular')) {
    releaseAngularModule(angularLibs)
  }
}

def releaseCdkModule() {
  try {
    sh """
      npm unpublish --force --registry $env.NPM_REGISTRY
    """
  } catch(err) {
    print err.getMessage()
  }
  sh """
    npm publish --registry $env.NPM_REGISTRY
  """
}

def releaseAngularModule(angularLibs) {
  for (lib in angularLibs) {
    try {
      sh """
        cd $workspace/dist/$lib
        npm unpublish --force --registry $env.NPM_REGISTRY
      """
    } catch(err) {
      print err.getMessage()
    }
    sh """
      cd $workspace/dist/$lib
      npm publish --registry $env.NPM_REGISTRY
    """
  }
}
