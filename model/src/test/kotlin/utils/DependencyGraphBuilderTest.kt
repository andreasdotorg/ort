/*
 * Copyright (C) 2021 Bosch.IO GmbH
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

package org.ossreviewtoolkit.model.utils

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.containExactly
import io.kotest.matchers.collections.containExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe

import java.util.SortedSet

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtIssue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Scope

class DependencyGraphBuilderTest : WordSpec({
    "DependencyGraphBuilder" should {
        "collect the direct dependencies of scopes" {
            val scope1 = "compile"
            val scope2 = "test"
            val dep1 = createDependency("org.apache.commons", "commons-lang3", "3.11")
            val dep2 = createDependency("org.apache.commons", "commons-collections4", "4.4")
            val dep3 = createDependency("my-project", "my-module", "1.0")

            val graph = createGraphBuilder()
                .addDependency(scope1, dep1)
                .addDependency(scope1, dep3)
                .addDependency(scope2, dep2)
                .addDependency(scope2, dep1)
                .build()

            graph.scopeRoots shouldHaveSize 3
            val scopes = graph.createScopes()
            scopes.map { it.name } should containExactlyInAnyOrder(scope1, scope2)

            val scope1Dependencies = scopeDependencies(scopes, scope1)
            scope1Dependencies should containExactlyInAnyOrder(dep1, dep3)

            val scope2Dependencies = scopeDependencies(scopes, scope2)
            scope2Dependencies should containExactlyInAnyOrder(dep2, dep1)
        }

        "collect information about packages" {
            val dep1 = createDependency("org.apache.commons", "commons-lang3", "3.11")
            val dep2 = createDependency("org.apache.commons", "commons-collections4", "4.4")
            val dep3 = createDependency("my-project", "my-module", "1.0")

            val packageIds = createGraphBuilder()
                .addDependency("s1", dep1)
                .addDependency("s2", dep2)
                .addDependency("s3", dep3)
                .packages()
                .map { it.id }

            packageIds should containExactlyInAnyOrder(dep1.id, dep2.id, dep3.id)
        }

        "deal with transitive dependencies correctly" {
            val scope1 = "compile"
            val scope2 = "test"
            val dep1 = createDependency("org.apache.commons", "commons-lang3", "3.11")
            val dep2 = createDependency("org.apache.commons", "commons-collections4", "4.4")
            val dep3 = createDependency(
                "org.apache.commons",
                "commons-configuration2",
                "2.7",
                dependencies = listOf(dep1, dep2)
            )
            val dep4 = createDependency("org.apache.commons", "commons-csv", "1.5", dependencies = listOf(dep1))
            val dep5 = createDependency("com.acme", "dep", "0.7", dependencies = listOf(dep3))

            val graph = createGraphBuilder()
                .addDependency(scope1, dep1)
                .addDependency(scope2, dep1)
                .addDependency(scope2, dep2)
                .addDependency(scope1, dep5)
                .addDependency(scope1, dep3)
                .addDependency(scope2, dep4)
                .build()

            graph.scopeRoots shouldHaveSize 2
            graph.scopeRoots.all { it.fragment == 0 } shouldBe true
            val scopes = graph.createScopes()

            val scopeDependencies1 = scopeDependencies(scopes, scope1)
            scopeDependencies1 should containExactly(dep5, dep3, dep1)

            val scopeDependencies2 = scopeDependencies(scopes, scope2)
            scopeDependencies2 should containExactlyInAnyOrder(dep1, dep2, dep4)
        }

        "deal with packages that have different dependencies in the same scope" {
            val scope = "TheScope"
            val depLang = createDependency("org.apache.commons", "commons-lang3", "3.11")
            val depLog = createDependency("commons-logging", "commons-logging", "1.2")
            val depConfig1 = createDependency(
                "org.apache.commons", "commons-configuration2", "2.7",
                dependencies = listOf(depLog, depLang)
            )
            val depConfig2 = createDependency(
                "org.apache.commons", "commons-configuration2", "2.7",
                dependencies = listOf(depLang)
            )
            val depAcme = createDependency(
                "com.acme", "lib-full", "1.0",
                dependencies = listOf(depConfig1, depLang)
            )
            val depAcmeExclude = createDependency(
                "com.acme", "lib-exclude", "1.1",
                dependencies = listOf(depConfig2)
            )
            val depLib = createDependency("com.business", "lib", "1", dependencies = listOf(depConfig1, depAcmeExclude))

            val graph = createGraphBuilder()
                .addDependency(scope, depAcme)
                .addDependency(scope, depLib)
                .build()

            val scopeDependencies = scopeDependencies(graph.createScopes(), scope)

            scopeDependencies should containExactly(depAcme, depLib)
        }

        "deal with packages that have different dependencies in different scopes" {
            val scope1 = "OneScope"
            val scope2 = "AnotherScope"
            val depLang = createDependency("org.apache.commons", "commons-lang3", "3.11")
            val depLog = createDependency("commons-logging", "commons-logging", "1.2")
            val depConfig1 = createDependency(
                "org.apache.commons", "commons-configuration2", "2.7",
                dependencies = listOf(depLog, depLang)
            )
            val depConfig2 = createDependency(
                "org.apache.commons", "commons-configuration2", "2.7",
                dependencies = listOf(depLang)
            )
            val depAcmeExclude = createDependency(
                "com.acme", "lib-exclude", "1.1",
                dependencies = listOf(depConfig2)
            )

            val graph = createGraphBuilder()
                .addDependency(scope1, depLog)
                .addDependency(scope1, depConfig1)
                .addDependency(scope2, depAcmeExclude)
                .build()
            val scopes = graph.createScopes()

            val scope1Dependencies = scopeDependencies(scopes, scope1)
            scope1Dependencies should containExactly(depLog, depConfig1)
            val scope2Dependencies = scopeDependencies(scopes, scope2)
            scope2Dependencies should containExactly(depAcmeExclude)
        }
    }
})

/**
 * A [DependencyHandler] implementation that operates on [PackageReference] objects. This is used to handle the
 * dependencies added to the builder under test.
 */
private object PackageRefDependencyHandler : DependencyHandler<PackageReference> {
    override fun identifierFor(dependency: PackageReference): Identifier = dependency.id

    override fun dependenciesFor(dependency: PackageReference): Collection<PackageReference> =
        dependency.dependencies

    override fun linkageFor(dependency: PackageReference): PackageLinkage = dependency.linkage

    override fun createPackage(dependency: PackageReference, issues: MutableList<OrtIssue>): Package =
        Package.EMPTY.copy(id = dependency.id)

    override fun issuesForDependency(dependency: PackageReference): Collection<OrtIssue> =
        dependency.issues
}

/**
 * Create a [DependencyGraphBuilder] equipped with a [PackageRefDependencyHandler] that is used by the test cases in
 * this class.
 */
private fun createGraphBuilder(): DependencyGraphBuilder<PackageReference> =
    DependencyGraphBuilder(PackageRefDependencyHandler)

/**
 * Create a test dependency with the properties provided.
 */
private fun createDependency(
    group: String,
    artifact: String,
    version: String,
    dependencies: List<PackageReference> = emptyList()
): PackageReference {
    val id = Identifier("test", group, artifact, version)
    return PackageReference(id, dependencies = dependencies.toSortedSet())
}

/**
 * Return the package references from the given [scopes] associated with the scope with the given [scopeName].
 */
private fun scopeDependencies(
    scopes: SortedSet<Scope>,
    scopeName: String
): Set<PackageReference> =
    scopes.find { it.name == scopeName }?.dependencies.orEmpty()
