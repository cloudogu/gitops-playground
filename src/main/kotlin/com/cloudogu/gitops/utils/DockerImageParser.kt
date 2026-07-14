package com.cloudogu.gitops.utils

class DockerImageParser {
    class Image(
        val registry: String,
        val repository: String,
        val tag: String
    ) {
        fun getRegistryAndRepositoryAsString(): String {
            if (registry.isEmpty()) {
                return repository
            }
            return "$registry/$repository"
        }

        override fun toString(): String {
            return "${getRegistryAndRepositoryAsString()}:$tag"
        }
    }

    companion object {
        @JvmStatic
        fun parse(image: String): Image {
            if (!image.contains(":")) {
                // Most helm charts expect an explicit image tag, otherwise they use the version set by the app.
                // This will likely be unexpected so force using a tag
                throw RuntimeException("Cannot set image '$image' due to missing tag. Must be the format '\$repository:\$tag'")
            }

            val tuple = splitTag(image)
            val imageWithoutTag = tuple.first
            val tag = tuple.second

            val parts = imageWithoutTag.split("/").toMutableList()
            val repository = parts.takeLast(2).joinToString("/")
            
            // Drop last 2 elements to get the registry part
            val registry = if (parts.size > 2) {
                parts.dropLast(2).joinToString("/")
            } else {
                ""
            }

            return Image(registry, repository, tag)
        }

        private fun splitTag(image: String): Pair<String, String> {
            val imageParts = image.split(":")
            val tag = imageParts.last()
            val imageWithoutTag = imageParts.dropLast(1).joinToString(":")
            return Pair(imageWithoutTag, tag)
        }
    }
}
