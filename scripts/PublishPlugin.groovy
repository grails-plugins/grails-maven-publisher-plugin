import groovyx.net.http.HTTPBuilder
import org.apache.commons.codec.digest.DigestUtils
import org.codehaus.groovy.grails.cli.CommandLineHelper

import static groovyx.net.http.Method.PUT
import static groovyx.net.http.ContentType.JSON

includeTargets << grailsScript("_GrailsPluginDev")
includeTargets << new File(mavenPublisherPluginDir, "scripts/_GrailsMaven.groovy")

USAGE = """
    publish-plugin [--repository=REPO] [--protocol=PROTOCOL] [--portal=PORTAL] [--dryRun] [--snapshot] [--pingOnly]

where
    REPO     = The name of a configured repository to deploy the plugin to. Can be
               a Subversion repository or a Maven-compatible one.
               (default: Grails Central Plugin Repository).

    PROTOCOL = The protocol to use when deploying to a Maven-compatible repository.
               Can be one of 'http', 'scp', 'scpexe', 'ftp', or 'webdav'.
               (default: 'http').
	           
    PORTAL   = The portal to inform of the plugin's release.
               (default: Grails Plugin Portal).
	           
    --dryRun   = Shows you what will happen when you publish the plugin, but doesn't
                 actually publish it.
	           
    --snapshot = Force this release to be a snapshot version, i.e. it isn't automatically
                 made the latest available release.

    --pingOnly = Don't publish/deploy the plugin, only send a notification to the
                 plugin portal. This is useful if portal notification failed during a
                 previous attempt to publish the plugin. Mutually exclusive with the
                 --dryRun option.
"""

target(default: "Publishes a plugin to either a Subversion or Maven repository.") {
    depends(parseArguments, processDefinitions, packagePlugin, generatePom)

    event("PublishPluginStart", [])
    
    // Use the Grails Central Plugin repository as the default.
    def repoClass = classLoader.loadClass("grails.plugins.publish.Repository")
    def repo = repoClass.grailsCentral

    def repoName = argsMap["repository"]
    def type = "svn"

    if (repoName) {
        // First look for the repository definition for this name. This
        // could either be from the newer Maven-based definitions or the
        // legacy Subversion-based ones.
        def repoDefn = distributionInfo.remoteRepos[repoName]
        def defaultPortal = null
        def url
        if (repoDefn) {
            type = repoDefn.args["type"] ?: "maven"
            url = repoDefn.args["url"]

            // If the repository defines a portal, then we should use that
            // ahead of the public Grails plugin portal.
            defaultPortal = repoDefn.args["portal"]
        }
        else {
            type = "svn"
            url = grailsSettings.config.grails.plugin.repos.distribution."$repoName"
        }
        
        // Check that the repository is defined.
        if (url) {
            repo = repoClass.newInstance(
                    repoName,
                    new URI(url),
                    defaultPortal ? new URI(distributionInfo.portals[defaultPortal]) : null)
            println "Publishing to ${type == 'svn' ? 'Subversion' : 'Maven'} repository '$repoName'"
        }
        else {
            println "No configuration found for repository '$repoName'"
            exit(1)
        }
    }
    else {
        println "Publishing to Grails Central"
    }

    // Handle old name for dry run option. Should be removed for 1.0 release.
    if (argsMap["dry-run"]) {
        println "WARN: The '--dry-run' option has been deprecated in favour of '--dryRun' for consistency with the release-plugin command."
        argsMap["dryRun"] = true
    }

    def deployer
    if (argsMap["dryRun"]) {
        deployer = classLoader.loadClass("grails.plugins.publish.print.DryRunDeployer").newInstance()
    }
    else if (type == "svn") {
        // Helper class for getting user input from the command line.
        def inputHelper = new CommandLineHelper()

        // Create a deployer for Subversion and...
        def svnClient = classLoader.loadClass("grails.plugins.publish.svn.SvnClient").newInstance(repo.uri.toString())
        def masterPluginList = classLoader.loadClass("grails.plugins.publish.svn.MasterPluginList").newInstance(
                svnClient,
                repo.name,
                new File(grailsSettings.projectWorkDir, ".plugin-meta"),
                System.out,
                false)

        deployer = classLoader.loadClass("grails.plugins.publish.svn.SvnDeployer").newInstance(
                svnClient,
                grailsSettings.projectWorkDir,
                repo.name,
                masterPluginList,
                System.out) { msg ->
            // This closure is executed whenever the deployer needs to
            // ask for user input.
            return inputHelper.userInput(msg)
        }
    }
    else if (type == "maven"){
        // Work out the protocol to use. This may be provided as a
        // '--protocol' argument on the command line or inferred from
        // the repository URL.
        def protocols = [
                http: "wagon-http",
                scp: "wagon-ssh",
                scpexe: "wagon-ssh-external",
                ftp: "wagon-ftp",
                webdav: "wagon-webdav" ]
        def protocol = protocols.http
        
        def repoDefn = distributionInfo.remoteRepos[repoName]
        repoDefn.args.remove "portal"

        if (argsMap["protocol"]) {
            protocol = protocols[argsMap["protocol"]]
        }
        else if (repo.uri) {
            if (!repo.uri.scheme) {
                println "Invalid URL for repository '${repo.name}': ${repo.uri}"
                exit(1)
                return 1
            }
            
            if (protocols[repo.uri.scheme]) {
                protocol = protocols[repo.uri.scheme]
            }
            else {
                println "WARNING: unknown protocol '${repo.uri.scheme}' for repository '${repo.name}'"
            }
        }
        
        deployer = classLoader.loadClass("grails.plugins.publish.maven.MavenDeployer").newInstance(ant, repoDefn, protocol)
    }
    else {
        println "Unknown type '$type' defined for repository '$repoName'"
        exit(1)
    }
    
    // Read the plugin information from the POM.
    def pluginInfo = new XmlSlurper().parse(new File(pomFileLocation))
    def isRelease = !pluginInfo.version.text().endsWith("-SNAPSHOT")
    if (argsMap["snapshot"]) isRelease = false
    
    if (!argsMap["pingOnly"]) {
        deployer.deployPlugin(new File(pluginZip), new File("plugin.xml"), new File(pomFileLocation), isRelease)
    }

    // Ping the plugin portal with the details of this release.
    if (!argsMap["dryRun"]) {
        // What's the URL of the portal to ping? The explicit 'portal' argument
        // takes precedence, then the portal configured for the current repository,
        // and finally the public Grails plugin portal.
        def portalUrl = repo.defaultPortal
        def portalName = argsMap["portal"]
        if (portalName) {
            // Pick the configured portal with the given name, assuming one
            // exists with that name.
            portalUrl = distributionInfo.portals[portalName]

            if (!portalUrl) {
                println "No portal defined with ID '${portalName}'"
                println "Plugin has been published, but the plugin portal has not been notified."
                exit 1
            }

            portalUrl = new URI(portalUrl)
        }
        else if (repoName) {
            // We don't ping the grails.org portal if a repository has been specified
            // but that repository has no default portal configured.
            println "No default portal defined for repository '${repoName}' - skipping portal notification"
            return
        }

        // Add the plugin name to the URL, making sure first that the base portal URI
        // ends with '/'. Otherwise the resolve won't do what we want.
        if (!portalUrl.path.endsWith("/")) portalUrl = new URI(portalUrl.toString() + "/")
        portalUrl = portalUrl.resolve(pluginInfo.artifactId.text())

        // Now that we have a URL, simply send a PUT request with the appropriate
        // JSON content.
        println "Notifying plugin portal '${portalUrl}' of release..."
        def inputHelper = new CommandLineHelper()
        def username = inputHelper.userInput("Username for portal (leave empty if authentication not required):")
        def password = inputHelper.userInput("Password for portal (leave empty if authentication not required):")

        def http = new HTTPBuilder(portalUrl)
        http.auth.basic username, password
        http.request(PUT, JSON) { req ->
            body = [
                name : pluginInfo.artifactId.text(),
                version : pluginInfo.version.text(),
                group : pluginInfo.groupId.text(),
                isSnapshot : !isRelease,
                url : repo.uri.toString()
            ]
            
            response.success = { resp ->
                println "Notification successful"
            }

            response.failure = { resp, json ->
                println "Notification failed - status ${resp.status} - ${json.message}"
            }
        }
    }
    
    event("PublishPluginEnd", [])
    
}

target(processDefinitions: "Reads the repository definition configuration.") {
    distributionInfo = new DistributionManagementInfo()
    if (grailsSettings.config.grails.project.dependency.distribution instanceof Closure) {
        def callable = grailsSettings.config.grails.project.dependency.distribution?.clone()
        callable.delegate = distributionInfo
        callable.resolveStrategy = Closure.DELEGATE_FIRST
        try {
            callable.call()				
        }
        catch (e) {
            println "Error reading dependency distribution settings: ${e.message}"
            exit 1
        }
    }
}

class DistributionManagementInfo {
    Map portals = [:]
    Map remoteRepos = [:]
    String local

    void localRepository(String s) { local = s }

    void remoteRepository(Map args, Closure c = null) {
        if (!args?.id) throw new Exception("Remote repository misconfigured: Please specify a repository 'id'. Eg. remoteRepository(id:'myRepo')")
        if (!args?.url) throw new Exception("Remote repository misconfigured: Please specify a repository 'url'. Eg. remoteRepository(url:'http://..')")
        remoteRepos[args.id] = new Expando(args: args, configurer: c)
    }

    void portal(Map args) {
        portals[args.id] = args.url
    }
}
