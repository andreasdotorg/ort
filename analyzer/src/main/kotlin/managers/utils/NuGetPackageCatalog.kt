/*
 * Copyright (C) 2022 Bosch.IO GmbH
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
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class NuGetPackageCatalog(
    val items: List<PackageCatalogPage>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PackageCatalogPage(
    val items: List<PackageCatalogItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PackageCatalogItem(
    val catalogEntry: PackageCatalogEntry
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PackageCatalogEntry(
    @JsonProperty("@id")
    val id: String,
    val title: String,
    val version: String,
    val authors: String,
    val description: String,
    val licenseExpression: String,
    val licenseUrl: String,
    val packageContent: String,
    val projectUrl: String
)
