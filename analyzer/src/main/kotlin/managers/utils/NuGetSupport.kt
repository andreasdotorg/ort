/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 * Copyright (C) 2019 Bosch Software Innovations GmbH
 * Copyright (C) 2020-2022 Bosch.IO GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.analyzer.managers.utils

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.xml.XmlFactory
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

import java.io.File
import java.io.IOException
import java.util.SortedMap
import java.util.SortedSet
import java.util.concurrent.TimeUnit

import javax.xml.bind.annotation.XmlRootElement

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

import okhttp3.CacheControl
import okhttp3.Request

import org.apache.maven.artifact.versioning.ArtifactVersion
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.apache.maven.artifact.versioning.VersionRange

import org.jsoup.Jsoup

import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManager.Companion.processPackageVcs
import org.ossreviewtoolkit.analyzer.managers.utils.NuGetAllPackageData.PackageDetails
import org.ossreviewtoolkit.analyzer.managers.utils.NuGetAllPackageData.PackageSpec
import org.ossreviewtoolkit.analyzer.managers.utils.NuGetAllPackageData.ServiceIndex
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtIssue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.orEmpty
import org.ossreviewtoolkit.utils.common.searchUpwardsForFile
import org.ossreviewtoolkit.utils.core.OkHttpClientHelper
import org.ossreviewtoolkit.utils.core.await
import org.ossreviewtoolkit.utils.core.log

// See https://docs.microsoft.com/en-us/nuget/api/overview.
private const val DEFAULT_SERVICE_INDEX_URL = "https://api.nuget.org/v3/index.json"
private const val REGISTRATIONS_BASE_URL_TYPE = "RegistrationsBaseUrl/3.6.0"

private const val NEAREST_FRAMEWORK_URL = "https://nugettools.azurewebsites.net/5.11.0/get-nearest-framework"

private val VERSION_RANGE_CHARS = charArrayOf('[', ']', '(', ')')

class NuGetSupport(serviceIndexUrls: List<String> = listOf(DEFAULT_SERVICE_INDEX_URL)) {
    companion object {
        val JSON_MAPPER = JsonMapper().registerKotlinModule()

        val XML_MAPPER = XmlMapper(XmlFactory().apply {
            // Work-around for https://github.com/FasterXML/jackson-module-kotlin/issues/138.
            xmlTextElementName = "value"
        }).registerKotlinModule()

        fun create(definitionFile: File): NuGetSupport {
            val configXmlReader = NuGetConfigFileReader()
            val configFile = definitionFile.parentFile.searchUpwardsForFile("nuget.config", ignoreCase = true)
            val serviceIndexUrls = configFile?.let { configXmlReader.getRegistrationsBaseUrls(it) }

            return serviceIndexUrls?.let { NuGetSupport(it) } ?: NuGetSupport()
        }
    }

    private val client = OkHttpClientHelper.buildClient {
        // Cache responses more aggressively than the NuGet registry's default of "max-age=120, must-revalidate"
        // (for the index URL) or even "no-store" (for the package data). More or less arbitrarily choose 7 days /
        // 1 week as dependencies of a released package should actually not change at all. But refreshing after a
        // few days when the typical incremental scanning / curation creating cycle is through should be fine.
        addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val cacheControl = CacheControl.Builder()
                .maxAge(7, TimeUnit.DAYS)
                .build()
            response.newBuilder()
                .header("cache-control", cacheControl.toString())
                .build()
        }
    }

    private val serviceIndices = runBlocking {
        serviceIndexUrls.map {
            async { mapFromUrl<ServiceIndex>(JSON_MAPPER, it) }
        }.awaitAll()
    }

    // Note: Remove a trailing slash as one is always added later to separate from the path, and a double-slash would
    // break the URL!
    private val registrationsBaseUrls = serviceIndices
        .flatMap { it.resources }
        .filter { it.type == REGISTRATIONS_BASE_URL_TYPE }
        .map { it.id.removeSuffix("/") }

    private val packageCatalogs = mutableMapOf<String, NuGetPackageCatalog>()
    private val packageDetails = mutableMapOf<String, PackageDetails>()
    private val packageSpecs = mutableMapOf<String, PackageSpec>()

    private suspend inline fun <reified T> mapFromUrl(mapper: ObjectMapper, url: String): T {
        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        val response = client.newCall(request).await()
        if (response.cacheResponse != null) {
            log.debug { "Retrieved '$url' response from local cache." }
        } else {
            log.debug { "Retrieved '$url' response from remote server." }
        }

        val body = response.body?.string()?.takeIf { response.isSuccessful }
            ?: throw IOException("Failed to get a response body from '$url'.")

        return mapper.readValue(body)
    }

    private fun getPackageCatalog(name: String): NuGetPackageCatalog =
        packageCatalogs.getOrPut(name) {
            registrationsBaseUrls.asSequence().firstNotNullOfOrNull { baseUrl ->
                runCatching {
                    val catalogUrl = "$baseUrl/${name.lowercase()}/index.json"
                    runBlocking { mapFromUrl<NuGetPackageCatalog>(JSON_MAPPER, catalogUrl) }
                }.getOrNull()
            } ?: throw IOException("Failed to retrieve package catalog for '$name' from any of $registrationsBaseUrls.")
        }

    private fun getPackageDetails(name: String, version: String): PackageDetails =
        packageDetails.getOrPut("$name:$version") {
            val catalog = getPackageCatalog(name)
            val detailsUrl =
                catalog.items.flatMap { it.items }.find { it.catalogEntry.version == version }?.catalogEntry?.id
            if (detailsUrl != null) {
                runBlocking { mapFromUrl(JSON_MAPPER, detailsUrl) }
            } else {
                throw IOException("Could not find details URL for version $version of NuGet package $name.")
            }
        }

    private fun getPackageSpec(name: String, version: String): PackageSpec =
        packageSpecs.getOrPut("$name:$version") {
            val catalog = getPackageCatalog(name)
            val specUrl = catalog.items.flatMap { it.items }
                .find { it.catalogEntry.version == version }?.catalogEntry?.packageContent
                // TODO: Replace substringBefore with normalizeVersion()
                ?.replace(".${version}.nupkg", ".nuspec")
            if (specUrl != null) {
                runBlocking { mapFromUrl(XML_MAPPER, specUrl) }
            } else {
                throw IOException("Could not find spec URL for version $version of NuGet package $name.")
            }
        }

    /**
     * Return a sorted list of available version for the provided package [name].
     */
    private fun getAvailableVersions(name: String): List<ArtifactVersion> =
        runCatching {
            getPackageCatalog(name).items.flatMap { page ->
                page.items.map { it.catalogEntry.version.toVersion() }
            }.sorted()
        }.getOrElse { emptyList() }

    private fun getPackage(id: Identifier): Package {
        val catalog = getPackageCatalog(id.name)
        val catalogEntry =
            catalog.items.flatMap { it.items }.find { it.catalogEntry.version == id.version }?.catalogEntry
            // TODO: Properly handle error.
                ?: throw IOException("Cannot find catalog for ${id.toCoordinates()}.")

        val details = getPackageDetails(id.name, id.version)
        val spec = getPackageSpec(id.name, id.version)

        val vcs = spec.metadata.repository?.let {
            VcsInfo(
                type = VcsType(it.type.orEmpty()),
                url = it.url.orEmpty(),
                revision = (it.commit ?: it.branch).orEmpty()
            )
        }.orEmpty()

        return with(details) {
            val homepageUrl = projectUrl.orEmpty()

            Package(
                id = getIdentifier(spec.metadata.id, version),
                authors = parseAuthors(spec),
                declaredLicenses = parseLicenses(spec),
                description = description.orEmpty(),
                homepageUrl = homepageUrl,
                binaryArtifact = RemoteArtifact(
                    url = catalogEntry.packageContent,
                    hash = Hash.create("$packageHashAlgorithm-$packageHash")
                ),
                sourceArtifact = RemoteArtifact.EMPTY,
                vcs = vcs,
                vcsProcessed = processPackageVcs(vcs, homepageUrl)
            )
        }
    }

    private fun buildDependencyTree(
        dependencies: List<Pair<Identifier, NuGetDependency>>,
        targetFramework: String,
        resolvedVersions: Set<Identifier> = emptySet()
    ): List<PackageReference> {
        if (dependencies.isEmpty()) return emptyList()

        // Resolve version for each package name.
        val resolutionResult = resolveVersions(dependencies, resolvedVersions)

        // Create list of next Level of dependencies.
        val dependencyDetails = resolutionResult.resolvedVersions.map { getPackageDetails(it.name, it.version) }
        val transitiveDependencies = dependencyDetails.associate { details ->
            val possibleFrameworks = details.dependencyGroups.mapNotNull { it.targetFramework }
            val nearestFramework = getNearestFramework(targetFramework, possibleFrameworks)

            Identifier("NuGet::${details.id}:${details.version}") to details.dependencyGroups.filter {
                it.targetFramework == null || it.targetFramework == nearestFramework
            }.flatMap { dependencyGroup ->
                dependencyGroup.dependencies.map { dependency ->
                    NuGetDependency(dependency.id, dependency.range.filterNot { it.isWhitespace() }, targetFramework)
                }
            }
        }

        // Call recursively for next level.
        val packageReferences = buildDependencyTree(
            dependencies = transitiveDependencies.flatMap { (parent, dependencies) ->
                dependencies.map { parent to it }
            },
            targetFramework = targetFramework,
            resolvedVersions = resolvedVersions + resolutionResult.resolvedVersions
        )

        return transitiveDependencies.map { (pkg, dependencies) ->
            val dependencyReferences = dependencies.mapNotNullTo(sortedSetOf()) { dependency ->
                resolutionResult.issues[pkg].orEmpty().find { it.first.name == dependency.name }?.let { (id, issue) ->
                    PackageReference(id = id, issues = listOf(issue))
                } ?: packageReferences.find { it.id.name == dependency.name }
            }

            PackageReference(
                id = pkg,
                dependencies = dependencyReferences
            )
        }
    }

    private data class VersionResolutionResult(
        val resolvedVersions: Set<Identifier>,
        val issues: Map<Identifier, List<Pair<Identifier, OrtIssue>>>
    )

    private fun resolveVersions(
        dependencies: List<Pair<Identifier, NuGetDependency>>,
        resolvedIds: Set<Identifier>
    ): VersionResolutionResult {
        val resolvedVersions = mutableSetOf<Identifier>()
        val issues = mutableMapOf<Identifier, MutableList<Pair<Identifier, OrtIssue>>>()

        dependencies.groupBy { it.second.name }.forEach { (name, variants) ->
            resolvedIds.find { it.name == name }?.let { resolvedId ->
                resolvedVersions += resolvedId

                variants.forEach { (parent, dependency) ->
                    if (!dependency.version.toVersionRange().containsVersion(resolvedId.version.toVersion())
                    ) {
                        issues.getOrPut(parent) { mutableListOf() } +=
                            Identifier("NuGet::$name:${dependency.version}") to OrtIssue(
                                source = "NuGet",
                                severity = Severity.ERROR,
                                message = "Already resolved version '${resolvedId.version}' of package '$name' does " +
                                        "not satisfy version requirement '${dependency.version}'."
                            )
                    }
                }

                return@forEach
            }

            val availableVersions = getAvailableVersions(name)

            val matchingVersion = availableVersions.find { version ->
                variants.all { (_, dependency) ->
                    dependency.version.toVersionRange().containsVersion(version)
                }
            }

            if (matchingVersion != null) {
                resolvedVersions += Identifier("NuGet::$name:$matchingVersion")
            } else {
                variants.forEach { (parent, dependency) ->
                    issues.getOrPut(parent) { mutableListOf() } +=
                        Identifier("NuGet::$name:${dependency.version}") to OrtIssue(
                            source = "NuGet",
                            severity = Severity.ERROR,
                            message = "Cannot find a version for ${dependency.name} that satisfies the version " +
                                    "requirement for this and all sibling dependencies."
                        )
                }
            }
        }

        return VersionResolutionResult(resolvedVersions, issues)
    }

    fun PackageManager.resolveNuGetDependencies(
        definitionFile: File,
        reader: XmlPackageFileReader
    ): ProjectAnalyzerResult {
        val workingDir = definitionFile.parentFile

        val issues = mutableListOf<OrtIssue>()

        val project = getProject(definitionFile, workingDir)

        val references = reader.getDependencies(definitionFile)
        val referencesByFramework = references.groupBy { it.targetFramework }

        val scopes = referencesByFramework.flatMap { (targetFramework, frameworkDependencies) ->
            frameworkDependencies.groupBy { it.developmentDependency }.map { (devDependency, dependencies) ->
                val allDependencies = (referencesByFramework[""].orEmpty()
                    .filter { it.developmentDependency == devDependency } + dependencies).toSet()

                val packageReferences = buildDependencyTree(
                    dependencies = allDependencies.map { project.id to it },
                    targetFramework = targetFramework
                ).toSortedSet()

                val scopeName = buildString {
                    if (devDependency) append("dev-")
                    if (targetFramework.isEmpty()) append("allTargetFrameworks") else append(targetFramework)
                }

                Scope(scopeName, packageReferences)
            }
        }.toSortedSet()

        val packages = scopes.flatMapTo(mutableSetOf()) { it.collectDependencies() }.map {
            getPackage(it)
        }.toSortedSet()

        return ProjectAnalyzerResult(project.copy(scopeDependencies = scopes), packages, issues)
    }

    private val nearestFrameworkResults = mutableMapOf<String, String?>()

    private fun getNearestFramework(targetFramework: String, possibleFrameworks: List<String>): String? =
        nearestFrameworkResults.getOrPut("$targetFramework:${possibleFrameworks.joinToString()}") {
            val url =
                "$NEAREST_FRAMEWORK_URL?project=$targetFramework&package=${possibleFrameworks.joinToString("%0A")}"
            OkHttpClientHelper.downloadText(url).getOrNull()?.let { html ->
                val doc = Jsoup.parse(html)
                val text = doc.select("div.results div.alert").first()?.text().orEmpty()
                if (text.isNotBlank() && !text.startsWith("None")) {
                    return@getOrPut text.substringAfter("(").substringBefore(")")
                }
            }

            log.debug {
                "Could not find nearest framework for target framework $targetFramework and possible frameworks " +
                        "${possibleFrameworks.joinToString()}."
            }

            null
        }
}

/**
 * Parse information about the licenses of a package from the given [spec].
 */
private fun parseLicenses(spec: PackageSpec?): SortedSet<String> {
    val license = spec?.metadata?.run {
        // Note: "licenseUrl" has been deprecated in favor of "license", see
        // https://docs.microsoft.com/en-us/nuget/reference/nuspec#licenseurl
        val licenseValue = license?.value?.takeUnless { license.type == "file" }
        licenseValue ?: licenseUrl?.takeUnless { it == "https://aka.ms/deprecateLicenseUrl" }
    }

    return setOfNotNull(license).toSortedSet()
}

/**
 * Parse information about the authors of a package from the given [spec].
 */
private fun parseAuthors(spec: PackageSpec?): SortedSet<String> =
    spec?.metadata?.authors?.split(',', ';').orEmpty()
        .map(String::trim)
        .filterNot(String::isEmpty)
        .toSortedSet()

/**
 * Try to find a .nuspec file for the given [definitionFile]. The file is looked up in the same directory.
 */
private fun resolveLocalSpec(definitionFile: File): File? =
    definitionFile.parentFile?.resolve(".nuspec")?.takeIf { it.isFile }

private fun getIdentifier(name: String, version: String) =
    Identifier(type = "NuGet", namespace = "", name = name, version = version)

data class NuGetDependency(
    val name: String,
    val version: String,
    val targetFramework: String,
    val developmentDependency: Boolean = false
)

interface XmlPackageFileReader {
    fun getDependencies(definitionFile: File): Set<NuGetDependency>
}

private fun PackageManager.getProject(
    definitionFile: File,
    workingDir: File
): Project {
    val spec = resolveLocalSpec(definitionFile)?.let { NuGetSupport.XML_MAPPER.readValue<PackageSpec>(it) }

    return Project(
        id = Identifier(
            type = managerName,
            namespace = "",
            name = spec?.metadata?.id ?: definitionFile.relativeTo(analysisRoot).invariantSeparatorsPath,
            version = spec?.metadata?.version.orEmpty()
        ),
        definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
        authors = parseAuthors(spec),
        declaredLicenses = parseLicenses(spec),
        vcs = VcsInfo.EMPTY,
        vcsProcessed = PackageManager.processProjectVcs(workingDir),
        homepageUrl = ""
    )
}

/**
 * A reader for XML-based NuGet configuration files, see
 * https://docs.microsoft.com/en-us/nuget/reference/nuget-config-file
 */
class NuGetConfigFileReader {
    @JsonIgnoreProperties(ignoreUnknown = true)
    @XmlRootElement(name = "configuration")
    private data class NuGetConfig(
        val packageSources: List<SortedMap<String, String>>
    )

    fun getRegistrationsBaseUrls(configFile: File): List<String> {
        val nuGetConfig = NuGetSupport.XML_MAPPER.readValue<NuGetConfig>(configFile)

        val (remotes, locals) = nuGetConfig.packageSources
            .mapNotNull { it["value"] }
            .partition { it.startsWith("http") }

        if (locals.isNotEmpty()) {
            // TODO: Handle local package sources.
            log.warn { "Ignoring local NuGet package sources $locals." }
        }

        return remotes
    }
}

private fun String.toVersionRange() =
    if (contains(",")) {
        val (firstVersion, secondVersion) = trim(*VERSION_RANGE_CHARS).split(",")
        if (firstVersion == secondVersion) {
            VersionRange.createFromVersionSpec("[$firstVersion]")
        } else {
            VersionRange.createFromVersionSpec(this)
        }
    } else {
        VersionRange.createFromVersionSpec("[$this]")
    }

private fun String.normalizeVersion() = substringBefore("+")

private fun String.toVersion() = DefaultArtifactVersion(normalizeVersion())
