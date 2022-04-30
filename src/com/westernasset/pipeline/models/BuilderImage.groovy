package com.westernasset.pipeline.models

class BuilderImage extends BasicDockerImage implements DockerImage {

    static BuilderImage from(def env, Map config) {
        if (config.builderImage != null) {
            return BuilderImage.fromImage(config.builderImage)
        }
        return BuilderImage.fromTag(env, config.builderTag)
    }

    static BuilderImage fromImage(String builderImageString) {
        if (builderImageString.contains(':')) {
            def builderImageComponents = builderImageString.split(':')
            if (builderImageComponents.length != 2) {
                throw new IllegalArgumentException('Invalid builder image syntax')
            }
            return new BuilderImage(builderImageComponents[0], builderImageComponents[1])
        } else {
            return new BuilderImage(builderImageString, 'latest')
        }
    }

    static BuilderImage fromTag(def env, String builderTag) {
        return new BuilderImage(env.IMAGE_REPO_URI + '/' + env.IMAGE_REPO_PROD_KEY + '/' + env.IMAGE_BUIDER_REPO, builderTag)
    }

    BuilderImage(String name, String builderTag) {
        super(name, builderTag)
    }

}
