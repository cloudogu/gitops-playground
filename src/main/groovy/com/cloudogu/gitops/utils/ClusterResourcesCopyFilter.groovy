package com.cloudogu.gitops.utils

class ClusterResourcesCopyFilter {

	static FileFilter forSubDir(String copyFromDirectory,
		String subDirToCopy) {
		return forSubDirs(copyFromDirectory,
			[subDirToCopy])
	}

	static FileFilter forSubDirs(String copyFromDirectory,
		Collection<String> subDirsToCopy) {
		if (!subDirsToCopy || subDirsToCopy.isEmpty()) {
			return allowAllFilter()
		}

		File srcRoot = new File(copyFromDirectory).canonicalFile

		Set<String> prefixes = subDirsToCopy.collect { String s ->
			String norm = s.replace('\\', '/')
			norm = norm.replaceAll('^/+', '').replaceAll('/+$', '')
			norm + '/'
		} as Set<String>

		Set<String> templateIncludePrefixes = ['apps/argocd/argocd/templates/'] as Set<String>

		return { File f ->
			File canon = f.canonicalFile
			String rel = srcRoot.toURI().relativize(canon.toURI()).toString()
			rel = rel.replace('\\', '/')

			if (rel == '' || rel == '.') {
				return true
			}

			boolean isDir = f.isDirectory()
			String relDir = rel.endsWith('/') ? rel : rel + '/'

			if (templateIncludePrefixes.any { String p -> (isDir ? relDir : rel).startsWith(p)
			}) {
				return true
			}

			if (rel.startsWith('apps/') && relDir.contains('/templates/')) {
				return false
			}

			if (isDir) {
				return prefixes.any { String p -> relDir == p || relDir.startsWith(p) || p.startsWith(relDir)
				}
			}

			prefixes.any { String p -> rel.startsWith(p)
			}
		} as FileFilter
	}

	private static FileFilter allowAllFilter() {
		return { File f -> true } as FileFilter
	}
}
