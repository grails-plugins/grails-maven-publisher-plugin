package grails.plugins.publish.svn

import grails.plugins.publish.PluginDeployer

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.codehaus.groovy.grails.plugins.publishing.DefaultPluginPublisher
import org.springframework.core.io.FileSystemResource
import org.tmatesoft.svn.core.SVNAuthenticationException

/**
 * Implementation of {@link PluginDeployer} that deploys plugin packages
 * to a Subversion repository using the layout convention mandated by
 * Grails. All the Subversion interaction is delegated to the {@link
 * SvnClient} bean.
 */
class SvnDeployer implements PluginDeployer {
    def svnClient
    def workDir
    def askUser
    def out
    def repoName
    def masterPluginList

    SvnDeployer(svnClient, workDir, repoName, masterPluginList, out, askUser) {
        this.svnClient = svnClient
        this.workDir = workDir
        this.repoName = repoName
        this.masterPluginList = masterPluginList
        this.out = out
        this.askUser = askUser
    }

    /**
     * Does all the work involved in deploying the given plugin package
     * to a Grails-compatible Subversion repository (configured at object
     * instantiation). This involves checking out the trunk of the
     * repository to a temporary directory (unless the current directory
     * is already a Subversion working copy for that URL), adding the
     * plugin package, XML plugin descriptor, and POM, and finally
     * committing the changes to the repository. It then creates the
     * relevant tags.
     * @param pluginPackage The location of the packaged plugin, i.e.
     * the zip file.
     * @param pluginXmlFile The location of the XML plugin descriptor.
     * @param pomFile The location of the POM (pom.xml).
     * @param isRelease If this is <code>true</code>, the plugin is
     * tagged as the latest release.
     */
    void deployPlugin(File pluginPackage, File pluginXmlFile, File pomFile, boolean isRelease) {
        // Extract information from the POM.
        def pom = new XmlSlurper().parseText(pomFile.text)
        def pluginName = pom.artifactId.text()
        def pluginVersion = pom.version.text()
        def basePath = "grails-${pluginName}"
        def trunk = "${basePath}/trunk"

        // Is the current directory a working copy for the Subversion
        // repository? If yes, we can use it to perform the commits.
        def useTempWc = false
        def wc = new File(".")
        if (!handleAuthentication { svnClient.isWorkingCopyForRepository(wc, trunk) }) {
            // The current directory isn't a working copy, so create
            // a temporary working directory for the Subversion
            // repository.
            useTempWc = true
            wc = new File(workDir, "publish-wc")
            cleanLocalWorkingCopy(wc)
        }

        // We want to commit the new version to the Subversion repository,
        // but to do that we must first ensure that the repository already
        // contains the plugin. If it doesn't, we need to add the path
        // before committing the files.
        handleAuthentication {
            if (useTempWc && !svnClient.pathExists(trunk)) {
                // Path does not exist, so create it now.
                out.println "Creating path '$trunk' in the repository"
                svnClient.createPath(trunk, "Adding '${pluginName}' plugin to the repository.")
            }
        }

        // Check out the trunk of the Subversion project to our temporary
        // working directory unless we're working with the current directory
        // as the working copy. In the latter case, we just do an update.
        if (useTempWc) {
            out.println "Checking out '$trunk' from the repository to a temporary location"
            handleAuthentication { svnClient.checkOut(wc, trunk) }
        }
        else {
            out.println "Updating your working copy"
            handleAuthentication { svnClient.update(wc) }
        }

        // Create SHA1 and MD5 checksums for the plugin package.
        def packageBytes = pluginPackage.readBytes()
        def sha1File = new File(wc, "${pluginPackage.name}.sha1")
        def md5File = new File(wc, "${pluginPackage.name}.md5")
        sha1File.text = DigestUtils.shaHex(packageBytes)
        md5File.text = DigestUtils.md5Hex(packageBytes)

        // Copy the plugin package, plugin descriptor, and POM files to
        // the working copy so that we can commit them.
        def baseName = FilenameUtils.getBaseName(pluginPackage.name) - "grails-"
        def destFiles = [ 
                new File(wc, pluginPackage.name),
                new File(wc, "${baseName}-plugin.xml"),
                new File(wc, "${baseName}.pom"),
                new File(wc, "plugin.xml"),               // Required for backwards compatibility
                sha1File,
                md5File ]
        copyIfNotSame(pluginPackage, destFiles[0])
        copyIfNotSame(pluginXmlFile, destFiles[1])
        copyIfNotSame(pomFile, destFiles[2])

        // <basename>-plugin.xml is the reference file containing extra plugin
        // metadata, but we also need to commit a 'plugin.xml' file for backwards
        // compatibility with both the plugin synchronisation script on grails.org
        // and the release-plugin command when it rebuilds the master plugin list
        // from the repository.
        copyIfNotSame(pluginXmlFile, destFiles[3])

        // Add these files so that they can be committed to the remote repository.
        handleAuthentication { svnClient.addFilesToSvn(destFiles) }

        // Commit the changes.
        out.println "Committing the new version of the plugin and its metadata to the repository"
        handleAuthentication {
            svnClient.commit(wc, "Releasing version ${pluginVersion} of the '${pluginName}' plugin.")
        }

        // Tag the release.
        out.println "Tagging this version of the plugin"
        handleAuthentication {
            svnClient.tag(
                    "${basePath}/trunk",
                    "${basePath}/tags",
                    "RELEASE_${pluginVersion.replaceAll('\\.','_')}",
                    "Tagging the ${pluginVersion} release of the '${pluginName}' plugin.")
        }

        // Do we make this the latest release too? Only if it's not a
        // snapshot version.
        if (isRelease) {
            out.println "Tagging this release as the latest"
            handleAuthentication {
                svnClient.tag(
                        "${basePath}/trunk",
                        "${basePath}/tags",
                        "LATEST_RELEASE",
                        "Making version ${pluginVersion} of the '${pluginName}' plugin the latest.")
            }
        }

        // Support for legacy Grails clients: update the master plugin list
        // in the Subversion repository.
        updatePluginList(pluginName, pluginVersion, isRelease)
    }

    /**
     * Deletes the contents of the given directory, but leaves the
     * directory itself in place.
     */
    protected final cleanLocalWorkingCopy(localWorkingCopy) {
        if (localWorkingCopy.exists()) {
            localWorkingCopy.deleteDir()
        }
        localWorkingCopy.mkdirs()
    }

    /**
     * Does the work necessary to update the master plugin list with the
     * details of the current release of the plugin.
     * @param pluginName The name of the plugin we're deploying.
     * @param pluginVersion The version of the plugin we're deploying.
     * @param makeLatest Whether this plugin release will be marked as
     * the latest.
     */
    protected final updatePluginList(pluginName, pluginVersion, makeLatest) {
        handleAuthentication {
            masterPluginList.update(
                    pluginName,
                    !makeLatest,
                    "Updating master plugin list for release ${pluginVersion} of plugin ${pluginName}")
        }
    }

    /**
     * Copies the source file to the destination file unless the two
     * locations are the same.
     */
    protected copyIfNotSame(srcFile, destFile) {
        if (srcFile.canonicalFile != destFile.canonicalFile) {
            FileUtils.copyFile(srcFile, destFile)
        }
    }

    /**
     * Executes a closure that may throw an SVNAuthenticationException.
     * If that exception is thrown, this method asks the user for his
     * username and password, updates the Subversion credentials and
     * tries to execute the closure again. Any exception thrown at that
     * point will propagate out.
     * @param askUser A closure taking a string argument that requests
     * input from the user and returns the response (the entered text
     * in other words).
     * @param c The closure to execute within the try/catch.
     */
    private handleAuthentication(c, authCount = 0) {
        try {
            return c()
        }
        catch (SVNAuthenticationException ex) {
            // Only allow three authentication attempts.
            if (authCount == 3) throw ex
            else if (authCount > 0) out.println "Authentication failed - please try again."

            def username = askUser("Enter your Subversion username: ")
            def password = askUser("Enter your Subversion password: ")
            svnClient.setCredentials(username, password)
            return handleAuthentication(c, ++authCount)
        }
    }
}
