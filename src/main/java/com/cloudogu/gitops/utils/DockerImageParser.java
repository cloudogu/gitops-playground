package com.cloudogu.gitops.utils;

public class DockerImageParser {
    public static class Image {
        private final String registry;
        private final String repository;
        private final String tag;

        public Image(String registry, String repository, String tag) {
            this.registry = registry;
            this.repository = repository;
            this.tag = tag;
        }

        public String getRegistryAndRepositoryAsString() {
            if (registry == null || registry.isEmpty()) {
                return repository;
            }
            return registry + "/" + repository;
        }

        public String getRegistry() {
            return registry;
        }

        public String getRepository() {
            return repository;
        }

        public String getTag() {
            return tag;
        }

        @Override
        public String toString() {
            return getRegistryAndRepositoryAsString() + ":" + tag;
        }
    }

    public static Image parse(String image) {
        if (!image.contains(":")) {
            throw new RuntimeException("Cannot set image '" + image + "' due to missing tag. Must be the format '$repository:$tag'");
        }

        ImageAndTag tuple = splitTag(image);
        String imageWithoutTag = tuple.imageWithoutTag();
        String tag = tuple.tag();

        String[] parts = imageWithoutTag.split("/");
        String repository;
        String registry;

        if (parts.length >= 2) {
            repository = parts[parts.length - 2] + "/" + parts[parts.length - 1];
            StringBuilder registryBuilder = new StringBuilder();
            for (int i = 0; i < parts.length - 2; i++) {
                if (i > 0) {
                    registryBuilder.append("/");
                }
                registryBuilder.append(parts[i]);
            }
            registry = registryBuilder.toString();
        } else {
            repository = imageWithoutTag;
            registry = "";
        }

        return new Image(registry, repository, tag);
    }

    private static ImageAndTag splitTag(String image) {
        int lastColon = image.lastIndexOf(':');
        String imageWithoutTag = image.substring(0, lastColon);
        String tag = image.substring(lastColon + 1);
        return new ImageAndTag(imageWithoutTag, tag);
    }

    private record ImageAndTag(String imageWithoutTag, String tag) {}
}
