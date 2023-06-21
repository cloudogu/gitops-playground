package com.cloudogu.gitops.utils

class DockerImageParser {
    static class Image {
        public String repository
        public String tag

        Image(String repository, String tag) {
            this.repository = repository
            this.tag = tag
        }

        Tuple2<String, String> splitRegistryAndRepository() {
            def parts = repository.split("/")

            def repository = parts.takeRight(2).join("/")
            parts = parts.dropRight(2)
            def registry = parts.join("/")

            return new Tuple2(registry, repository)
        }
    }

    static Image parse(String image) {
        if (!image.contains(":")) {
            throw new RuntimeException("Cannot set image '$image' due to missing tag. Must be the format '\$repository:\$tag'")
        }

        String[] imageParts = image.split(":")
        String tag = imageParts.last()
        imageParts = imageParts.dropRight(1)

        return new Image(imageParts.join(":"), tag)
    }
}
