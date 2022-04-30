## Jenkins Shared Library

The goal for this Shared Library is to promote the standard build release process and ease the use Jenkins as the CI/CD tool for applications.  For a full list of project types supported by the WAM Shared Library, [visit this Confluence page](https://confluence.westernasset.com/display/DOC/Jenkins+Project+Types+Explained)

#### About the build & deploy instruction files: Jenkinsfile, Dockerfile and deployment YAML

In Jenkins, the project will be configured as a Multibranch pipeline project.  This means, Jenkins will automatically detect the new branches, automatically construct a build job and start your build.

By default, Jenkins will automatically build the master branch, and any branch matching "developer*".  However, you may configure your project to scan for any expression you'd like.

In order to build in Jenkins, it is required to provide a Jenkinsfile at the project root folder.  The details of how to construct this file using this shared library will be provided in the next three sections.

For a project, where the final generated build artifact from the Jenkins build process is a Docker image, a Dockerfile is required and it should be placed at the project root folder (Same level as your Jenkinsfile).

In order to deploy a Docker application to DEV/QA/PROD environments, you will have Kubernetes YAML files contained in a folder called "kubernetes", which is in the root of your project.

More details of how to configure the Jenkins project and full project build life cycle [is documented in Confluence here](https://confluence.westernasset.com/display/DOC/DevOps+Pipeline+Onboarding)

#### Jenkinsfile for Java application server

For Java applications, it usually includes the Maven build & Docker build process in the Jenkins build process.  The following Jenkinsfile template can be used to construct the application specific Jenkinsfile by filling those options:

```bash
@Library("wam-pipelines") _

snapshotBuildPipeline {
    projectType = "mavanDockerService"

    //git scm repo location in SSH fashion
    gitScm = "git@github.westernasset.com:orginzation/project.git"

    //build tool tag, in this example, we are using the java 8, maven 3.5.0 and sencha command 6.5.1.240
    builderTag = "j8-m3.5.0-s6.5.1.240"
    
    //nonprod DEV/QA environments
    nonProdEnvs = ["dev", "qa"]

    //QA envrionments
    qaEnvs = ["qa"]

    //Prod Kubernetes environment: pasx (pasadena production) or scx (santa clara production)
    prodEnv = "scx"

    //DR Kubernetes environment: pasx (pasadena production) or scx (santa clara production)
    drEnv = "pasx"

    /**
    * liquibase change file location -
    * also the project name need to defined in Jenkins as ENV at the project folder level
    * as -> liquibaseProjectFolder=wam-commons
    **/
    liquibaseChange = "src/main/resources/liquibase/changelog.xml"

    //liquibase tool tag
    liquibaseTag = "liquibase3.5.3"
}
```

#### Jenkinsfile for Java Batch

For the Java script project, it will have the Maven build & Docker build process.

```bash
@Library("wam-pipelines") _

snapshotBuildPipeline {
    projectType = "mavenDockerBatch"

    //git scm repo location in SSH fashion
    gitScm = "git@github.westernasset.com:orginzation/project.git"

    //build tool tag, in this example, we are using the java 8, maven 3.5.0 and sencha command 6.5.1.240
    builderTag = "j8-m3.5.0-s6.5.1.240"

    //Prod Kubernetes environment: pasx (pasadena production) or scx (santa clara production)
    prodEnv = "pasx"

    //DR Kubernetes environment: pasx (pasadena production) or scx (santa clara production)
    drEnv = "scx"

    /**
    * liquibase change file location -
    * also the project name need to defined in Jenkins as ENV at the project folder level
    * as -> liquibaseProjectFolder=wam-commons
    **/
    liquibaseChange = "src/main/resources/liquibase/changelog.xml"

    //liquibase tool tag
    liquibaseTag = "liquibase3.5.3"
}
```

#### Jenkins file for JAVA library

For the JAVA library, it usually includes only the maven build in the Jenkins build process.  So, here is the Jenkinsfile template:

```bash
@Library("wam-pipelines") _

snapshotBuildPipeline {
    projectType = "mavenLib"

    //git scm repo location in SSH fashion
    gitScm = "git@github.westernasset.com:orginzation/project.git"

    //build tool tag, in this example, we are using the java 8, maven 3.5.0 and sencha command 6.5.1.240
    builderTag = "j8-m3.5.0-s6.5.1.240"
}
```

#### Jenkinsfile for the NONE-JAVA application

For the none-JAVA type application, assuming the multiple stages build processes are used, usually only the Docker build is included in the Jenkins build process.  Here is the Jenkinsfile template can be used for this type of the project:

```bash
@Library("wam-pipelines") _

snapshotBuildPipeline {
    projectType = "dockerService"

    //git scm repo location in SSH fashion
    gitScm = "git@github.westernasset.com:orginzation/project.git"

    //nonprod DEV/QA environments
    nonProdEnvs = ["dev", "qa"]

    //QA envrionments
    qaEnvs = ["qa"]

    //Prod Kubernetes environment: pasx (pasadena production) or scx (santa clara production)
    prodEnv = "scx"
    
    //DR Kubernetes environment (opposite of prodEnv)
    drEnv = "pasx"

    //release version
    releaseVersion = "7.0"

}
```
