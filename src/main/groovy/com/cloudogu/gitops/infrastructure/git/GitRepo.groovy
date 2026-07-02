package com.cloudogu.gitops.infrastructure.git

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.cli.Version
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope
import com.cloudogu.gitops.infrastructure.git.providers.Scope
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import com.cloudogu.gitops.utils.jgit.helpers.InsecureCredentialProvider

import groovy.util.logging.Slf4j

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.PushCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

@Slf4j
class GitRepo {

	static final String NAMESPACE_3RD_PARTY_DEPENDENCIES = '3rd-party-dependencies'

	private final DeploymentContext context
	public GitProvider gitProvider
	private final FileSystemUtils fileSystemUtils

	private final String repoTarget
	private final boolean insecure
	private final String gitName
	private final String gitEmail

	private Git gitMemoization
	private final String absoluteLocalRepoTmpDir

	GitRepo(DeploymentContext context,
		GitProvider gitProvider,
		String repoTarget,
		FileSystemUtils fileSystemUtils) {
		def tmpDir = File.createTempDir()
		tmpDir.deleteOnExit()
		this.absoluteLocalRepoTmpDir = tmpDir.absolutePath
		this.context = context
		this.gitProvider = gitProvider
		this.fileSystemUtils = fileSystemUtils

		this.repoTarget = "${config.application.namePrefix}${repoTarget}"

		this.insecure = config.application.insecure
		this.gitName = config.application.gitName
		this.gitEmail = config.application.gitEmail
	}

	private Config getConfig() {
		return context.config
	}

	String getRepoTarget() {
		return repoTarget
	}

	boolean createRepositoryAndSetPermission(String description, boolean initialize = true) {
		def isNewRepo = this.gitProvider.createRepository(repoTarget, description, initialize)
		if (gitProvider.getGitOpsUsername()) {
			gitProvider.setRepositoryPermission(repoTarget,
				gitProvider.getGitOpsUsername(),
				AccessRole.WRITE,
				Scope.USER)
		}
		return isNewRepo

	}

	String getAbsoluteLocalRepoTmpDir() {
		return absoluteLocalRepoTmpDir
	}

	void cloneRepo() {
		def cloneUrl = getGitRepositoryUrl()
		log.debug("Cloning ${repoTarget}, Origin: ${cloneUrl}")
		Git.cloneRepository()
			.setURI(cloneUrl)
			.setDirectory(new File(absoluteLocalRepoTmpDir))
			.setCredentialsProvider(getCredentialProvider())
			.call()
	}

	void initLocalRepoIfNeeded() {
		File localRepoDir = new File(getAbsoluteLocalRepoTmpDir())
		File gitDir = new File(localRepoDir, '.git')

		if (gitDir.exists()) {
			log.debug("Local git repository already initialized at ${localRepoDir}")
			return
		}

		log.debug("Initializing local git repository at ${localRepoDir}")

		localRepoDir.mkdirs()

		Git git = Git.init()
			.setDirectory(localRepoDir)
			.call()

		// Configure the 'origin' remote so init'd repos behave like cloned ones.
		// pullRebaseMain() pulls from the remote name 'origin'; without this the
		// repo has no remote.origin.url and JGit fails with
		// "No value for key remote.origin.url found in configuration".
		git.remoteAdd()
			.setName('origin')
			.setUri(new URIish(getGitRepositoryUrl()))
			.call()

		git.close()
	}

	void pullRebaseMain() {
		log.debug('Pulling remote main with rebase for repo {}', repoTarget)

		getGit()
			.pull()
			.setRemote('origin')
			.setRemoteBranchName('main')
			.setRebase(true)
			.setCredentialsProvider(getCredentialProvider())
			.call()
	}

	void commitAndPush(String message, String tag) {
		commitAndPush(message, tag, 'HEAD:refs/heads/main')
	}

	void commitAndPush(String commitMessage, String tag, String refSpec) {
		log.debug("Adding files to ${repoTarget}")

		def git = getGit()
		git.add().addFilepattern('.').call()

		if (git.status().call().hasUncommittedChanges()) {
			log.debug("Commiting ${repoTarget}")

			git.commit()
				.setSign(false)
				.setMessage(commitMessage)
				.setAuthor(gitName, gitEmail)
				.setCommitter("${gitName} - GOP v${Version.NAME.split(',')[0].replace('(', '')}", gitEmail)
				.call()

			def pushCommand = createPushCommand(refSpec)

			if (tag) {
				log.debug("Setting tag '${tag}' on repo: ${repoTarget}")

				// Delete existing tags first to get idempotence
				git.tagDelete().setTags(tag).call()
				git.tag()
					.setName(tag)
					.call()

				pushCommand.setPushTags()
			}

			log.debug("Pushing repo: ${repoTarget}, refSpec: ${refSpec}")

			def pushResults = pushCommand.call()

			pushResults.each { result ->
				result.remoteUpdates.each { update ->
					log.debug("Push result for repo '{}': remoteName='{}', status='{}', message='{}'",
						repoTarget,
						update.remoteName,
						update.status,
						update.message)

					if (update.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK && update.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE) {
						throw new RuntimeException("Push failed for repo '${repoTarget}', remoteName='${update.remoteName}', status='${update.status}', message='${update.message}'")
					}
				}
			}
		} else {
			log.debug("No changes after add, nothing to commit or push on repo: ${repoTarget}")
		}
	}

	void commitAndPush(String commitMessage) {
		commitAndPush(commitMessage, null, 'HEAD:refs/heads/main')
	}

	/**
	 * Push all refs, i.e. all tags and branches*/

	void pushAll(boolean force) {
		createPushCommand('refs/*:refs/*').setForce(force).call()
	}

	void pushRef(String ref, boolean force) {
		pushRef(ref, ref, force)
	}

	void pushRef(String ref, String targetRef, boolean force) {
		createPushCommand("${ref}:${targetRef}").setForce(force).call()
	}

	/**
	 * Delete all files in this repository*/
	void clearRepo() {
		fileSystemUtils.deleteFilesExcept(new File(absoluteLocalRepoTmpDir), '.git')
	}

	void copyDirectoryContents(String srcDir) {
		copyDirectoryContents(srcDir, (FileFilter) null)
	}

	void copyDirectoryContents(String srcDir, FileFilter fileFilter) {
		if (!srcDir) {
			log.warn('Source directory is not defined. Nothing to copy?')
			return
		}

		log.debug("Initializing repo $repoTarget from $srcDir")
		String absoluteSrcDirLocation = new File(srcDir).isAbsolute() ? srcDir : "${fileSystemUtils.getRootDir()}/${srcDir}"
		fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir, fileFilter)
	}

	void writeFile(String path, String content) {
		def file = new File("$absoluteLocalRepoTmpDir/$path")
		fileSystemUtils.createDirectory(file.parent)
		file.createNewFile()
		file.text = content
	}

	void replaceTemplates(Map parameters) {
		new TemplatingEngine().replaceTemplates(new File(absoluteLocalRepoTmpDir), parameters)
	}

	String getGitRepositoryUrl() {
		return this.gitProvider.repoUrl(repoTarget, RepoUrlScope.CLIENT)
	}

	void checkoutRemoteMainIfLocalMainMissing() {
		initLocalRepoIfNeeded()

		def git = getGit()

		git.fetch()
			.setRemote('origin')
			.setCredentialsProvider(getCredentialProvider())
			.call()

		def localMain = git.repository.findRef('refs/heads/main')
		def remoteMain = git.repository.findRef('refs/remotes/origin/main')

		if (localMain != null) {
			git.checkout()
				.setName('main')
				.call()
			return
		}

		if (remoteMain != null) {
			log.debug("Creating local main branch from origin/main for repo '{}'", repoTarget)

			git.checkout()
				.setCreateBranch(true)
				.setName('main')
				.setStartPoint('origin/main')
				.call()
			return
		}

		log.debug("No local or remote main branch exists yet for repo '{}'. Keeping initialized repository.", repoTarget)
	}

	static boolean isCommit(File repoPath, String ref) {
		if (!ref) {
			return false
		}

		try (Git git = Git.open(repoPath)) {
			// Get all branch and tag names
			def allRefs = []

			// Add all branch names (without refs/heads/ prefix)
			git.branchList().call().each { branch -> allRefs.add(branch.name.replaceFirst('refs/heads/', ''))
			}

			// Add all tag names (without refs/tags/ prefix)
			git.tagList().call().each { tag -> allRefs.add(tag.name.replaceFirst('refs/tags/', ''))
			}

			// If the ref matches any branch or tag name, it's not a commit hash
			if (allRefs.contains(ref)) {
				return false
			}

			// If it's not a branch or tag, try to resolve it as a commit
			def objectId = git.repository.resolve(ref)
			return objectId != null

		}
	}

	/**
	 * checks, if file exists in repo in some branch.
	 * @param pathToRepo
	 * @param filename
	 */
	static boolean existFileInSomeBranch(String repo, String filename) {
		String filenameToSearch = filename
		File repoPath = new File(repo + '/.git')

		try (def git = Git.open(repoPath)) {
			List<Ref> branches = git
				.branchList()
				.setListMode(ListBranchCommand.ListMode.ALL)
				.call()

			for (Ref branch : branches) {
				String branchName = branch.getName()

				ObjectId commitId = git.repository.resolve(branchName)
				if (commitId == null) {
					continue
				}
				try (RevWalk revWalk = new RevWalk(git.repository)) {
					RevCommit commit = revWalk.parseCommit(commitId)
					try (TreeWalk treeWalk = new TreeWalk(git.repository)) {

						treeWalk.addTree(commit.getTree())
						treeWalk.setFilter(PathFilter.create(filenameToSearch))

						if (treeWalk.next()) {
							log.debug("File ${filename} found in branch ${branchName}")

							return true
						}
					}
				}
			}
		}
		log.debug("File ${filename} not found in repository ${repoPath}")
		return false
	}

	static boolean isTag(File repo, String ref) {
		if (!ref) {
			return false
		}
		try (def git = Git.open(repo)) {
			git.tagList().call().any { it.name.endsWith('/' + ref) || it.name == ref }
		}
	}

	private PushCommand createPushCommand(String refSpec) {
		return getGit()
			.push()
			.setRemote(getGitRepositoryUrl())
			.setRefSpecs(new RefSpec(refSpec))
			.setCredentialsProvider(getCredentialProvider())
	}

	private Git getGit() {
		if (gitMemoization != null) {
			return gitMemoization
		}

		return gitMemoization = Git.open(new File(absoluteLocalRepoTmpDir))
	}

	private CredentialsProvider getCredentialProvider() {
		def auth = this.gitProvider.getCredentials()
		def passwordAuthentication = new UsernamePasswordCredentialsProvider(auth.username, auth.password)
		return insecure ? new ChainingCredentialsProvider(new InsecureCredentialProvider(), passwordAuthentication) : passwordAuthentication
	}

}