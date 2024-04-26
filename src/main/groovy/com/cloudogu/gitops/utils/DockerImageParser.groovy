package com.cloudogu.gitops.utils

class DockerImageParser {
    static class Image {
        public String registry
        public String repository
        public String tag

        Image(String registry, String repository, String tag) {
            this.registry = registry
            this.repository = repository
            this.tag = tag
        }

        String getRegistryAndRepositoryAsString() {
            if (registry === "") {
                return repository
            }

            return "$registry/$repository"
        }

        String getRegistry() {
            return registry
        }

        String getRepository() {
            return repository
        }

        String getTag() {
            return tag
        }

        @Override
        String toString() {
            return getRegistryAndRepositoryAsString() + ":$tag"
        }
    }

    static Image parse(String image) {
        if (!image.contains(":")) {
            throw new RuntimeException("Cannot set image '$image' due to missing tag. Must be the format '\$repository:\$tag'")
        }

        // docker.io   /   foo/bar      :   latest
        // ^ registry      ^ repository     ^ tag
        // ^ ------------- image -----------------
        def tuple = splitTag(image)
        def imageWithoutTag = tuple.v1
        def tag = tuple.v2

        def parts = imageWithoutTag.split("/")
        def repository = parts.takeRight(2).join("/")
        parts = parts.dropRight(2)
        def registry = parts.join("/")

        return new Image(registry, repository, tag)
    }

    private static Tuple2<String, String> splitTag(String image) {
        String[] imageParts = image.split(":")
        String tag = imageParts.last()
        def imageWithoutTag = imageParts.dropRight(1).join(":")

        return new Tuple2(imageWithoutTag, tag)
    }
}
