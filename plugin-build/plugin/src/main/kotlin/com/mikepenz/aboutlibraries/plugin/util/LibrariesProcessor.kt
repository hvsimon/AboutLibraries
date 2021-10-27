package com.mikepenz.aboutlibraries.plugin.util

import com.mikepenz.aboutlibraries.plugin.mapping.Library
import com.mikepenz.aboutlibraries.plugin.mapping.License
import com.mikepenz.aboutlibraries.plugin.model.CollectedContainer
import com.mikepenz.aboutlibraries.plugin.model.ResultContainer
import com.mikepenz.aboutlibraries.plugin.util.LicenseUtil.fetchRemoteLicense
import com.mikepenz.aboutlibraries.plugin.util.PomLoader.resolvePomFile
import com.mikepenz.aboutlibraries.plugin.util.parser.LibraryReader
import com.mikepenz.aboutlibraries.plugin.util.parser.LicenseReader
import com.mikepenz.aboutlibraries.plugin.util.parser.PomReader
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.regex.Pattern

class LibrariesProcessor(
    private val dependencyHandler: DependencyHandler,
    private val collectedDependencies: CollectedContainer,
    private val configFolder: File?,
    private val exclusionPatterns: List<Pattern>,
    private val fetchRemoteLicense: Boolean,
    private var variant: String? = null,
    private var gitHubToken: String? = null
) {
    private val handledLibraries = HashSet<String>()
    private var rateLimit = 0

    fun gatherDependencies(): ResultContainer {
        if (fetchRemoteLicense) {
            LOGGER.debug("Will fetch remote licenses from repository.")
            rateLimit = LicenseUtil.availableGitHubRateLimit(gitHubToken)
        }

        val collectedDependencies = collectedDependencies.dependenciesForVariant(variant)
        println("All dependencies.size=${collectedDependencies.size}")

        val librariesList = ArrayList<Library>()
        val licensesMap = HashMap<String, License>()
        for (dependency in collectedDependencies) {
            val groupArtifact = dependency.key.split(":")
            val version = dependency.value.first()
            val versionIdentifier = DefaultModuleVersionIdentifier.newId(groupArtifact[0], groupArtifact[1], version)
            val file = dependencyHandler.resolvePomFile(groupArtifact[0], versionIdentifier, false)
            if (file != null) {
                try {
                    parseDependency(file)?.let {
                        val (lib, licenses) = it
                        librariesList.add(lib)
                        licenses.forEach { lic ->
                            licensesMap[lic.hash] = lic
                        }
                    }
                } catch (ex: Throwable) {
                    LOGGER.error("--> Failed to write dependency information for: $groupArtifact", ex)
                }
            }
        }

        if (configFolder != null) {
            LicenseReader.readLicenses(configFolder).forEach {
                // overlapping hash ?!
                licensesMap[it.hash] = it
            }

            LibraryReader.readLibraries(configFolder).forEach {
                LOGGER.error("Found custom: $it")
                librariesList.add(it) // merge with existing libraries!
            }
        }


        return ResultContainer(librariesList, licensesMap)
    }

    private fun parseDependency(artifactFile: File): Pair<Library, Set<License>>? {
        var artifactPomText = artifactFile.readText().trim()
        if (artifactPomText[0] != '<') {
            LOGGER.warn("--> ${artifactFile.path} contains a invalid character at the first position. Applying workaround.")
            artifactPomText = artifactPomText.substring(artifactPomText.indexOf('<'))
        }

        val pomReader = PomReader(artifactFile.inputStream())
        val uniqueId = pomReader.groupId + ":" + pomReader.artifactId

        for (pattern in exclusionPatterns) {
            if (pattern.matcher(uniqueId).matches()) {
                println("--> Skipping ${uniqueId}, matching exclusion pattern")
                return null
            }
        }

        LOGGER.debug("--> ArtifactPom for [{}:{}]:\n{}\n\n", pomReader.groupId, pomReader.artifactId, artifactPomText)

        // check if we shall skip this specific uniqueId
        if (shouldSkip(uniqueId)) {
            return null
        }

        // remember that we handled the library
        handledLibraries.add(uniqueId)

        // we also want to check if there are parent POMs with additional information
        var parentPomReader: PomReader? = null
        if (pomReader.hasParent()) {
            val parentGroupId = pomReader.parentGroupId
            val parentArtifactId = pomReader.parentArtifactId
            val parentVersion = pomReader.parentVersion

            if (parentGroupId != null && parentArtifactId != null && parentVersion != null) {
                val parentPomFile = dependencyHandler.resolvePomFile(
                    uniqueId,
                    DefaultModuleVersionIdentifier.newId(parentGroupId, parentArtifactId, parentVersion),
                    true
                )
                if (parentPomFile != null) {
                    val parentPomText = parentPomFile.readText()
                    LOGGER.debug("--> ArtifactPom ParentPom for [{}:{}]:\n{}\n\n", pomReader.groupId, pomReader.artifactId, parentPomText)
                    parentPomReader = PomReader(parentPomFile.inputStream())
                } else {
                    LOGGER.warn(
                        "--> ArtifactPom reports ParentPom for [{}:{}] but couldn't resolve it",
                        pomReader.groupId,
                        pomReader.artifactId
                    )
                }
            } else {
                LOGGER.info("--> Has parent pom, but misses info [{}:{}:{}]", parentGroupId, parentArtifactId, parentVersion)
            }
        } else {
            LOGGER.debug("--> No Artifact Parent Pom found for [{}:{}]", pomReader.groupId, pomReader.artifactId)
        }

        // get the url for the author
        var libraryName = fixLibraryName(uniqueId, chooseValue(uniqueId, "name", pomReader.name) { parentPomReader?.name } ?: "") // get name of the library
        val libraryDescription =
            fixLibraryDescription(uniqueId, chooseValue(uniqueId, "description", pomReader.description) { parentPomReader?.description } ?: "")

        val artifactVersion = chooseValue(uniqueId, "version", pomReader.version) { parentPomReader?.version } // get the version of the library
        if (artifactVersion.isNullOrBlank()) {
            LOGGER.info("----> Failed to identify version for: $uniqueId")
        }
        val libraryWebsite = chooseValue(uniqueId, "homePage", pomReader.homePage) { parentPomReader?.homePage } // get the url to the library

        // the list of licenses a lib may have
        val licenses = (chooseValue(uniqueId, "licenses", pomReader.licenses) { parentPomReader?.licenses })?.map {
            License(it.name, it.url, year = resolveLicenseYear(uniqueId, it.url))
        }?.toHashSet()

        val scm = chooseValue(uniqueId, "scm", pomReader.scm) { parentPomReader?.scm }
        if (licenses != null) {
            rateLimit = fetchRemoteLicense(uniqueId, scm, licenses, rateLimit, gitHubToken)
        }

        if (libraryName.isBlank()) {
            LOGGER.info("Could not get the name for ${uniqueId}! Fallback to '$uniqueId'")
            libraryName = uniqueId
        }

        val developers = chooseValue(uniqueId, "developers", pomReader.developers) { parentPomReader?.developers } ?: emptyList()
        val organization = chooseValue(uniqueId, "organization", pomReader.organization) { parentPomReader?.organization }

        val library = Library(
            uniqueId,
            artifactVersion,
            libraryName,
            libraryDescription,
            libraryWebsite,
            developers,
            organization,
            scm,
            licenses?.map { it.hash }?.toSet() ?: emptySet(),
            artifactFile.parentFile?.parentFile // artifactFile references the pom directly
        )

        LOGGER.debug("Adding library: {}", library)
        return library to (licenses ?: emptySet())
    }

    /**
     * Ensures and applies fixes to the library names (shorten, ...)
     */
    private fun fixLibraryName(uniqueId: String, value: String): String {
        return if (value.startsWith("Android Support Library")) {
            value.replace("Android Support Library", "Support")
        } else if (value.startsWith("Android Support")) {
            value.replace("Android Support", "Support")
        } else if (value.startsWith("org.jetbrains.kotlin:")) {
            value.replace("org.jetbrains.kotlin:", "")
        } else {
            value
        }
    }

    /**
     * Ensures and applies fixes to the library descriptions (remove 'null', ...)
     */
    private fun fixLibraryDescription(uniqueId: String, value: String): String {
        return value.takeIf { it != "null" } ?: ""
    }

    private fun resolveLicenseYear(uniqueId: String, repositoryLink: String?): String? {
        return null
    }

    /**
     * Skip libraries which have a core dependency and we don't want it to show up more than necessary
     */
    private fun shouldSkip(uniqueId: String): Boolean {
        return handledLibraries.contains(uniqueId) || uniqueId == "com.mikepenz:aboutlibraries" || uniqueId == "com.mikepenz:aboutlibraries-definitions"
    }

    companion object {
        internal val LOGGER: Logger = LoggerFactory.getLogger(LibrariesProcessor::class.java)
    }
}
