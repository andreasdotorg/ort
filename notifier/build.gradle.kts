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

val apacheCommonsEmailVersion: String by project
val jiraRestClientVersion: String by project
val mockkVersion: String by project
val wiremockVersion: String by project

plugins {
    // Apply core plugins.
    `java-library`
}

repositories {
    exclusiveContent {
        forRepository {
            maven("https://packages.atlassian.com/maven-external")
        }

        filter {
            includeGroupByRegex("com\\.atlassian\\..*")
        }
    }
}

dependencies {
    api(project(":model"))
    api(project(":utils:scripting-utils"))

    implementation(project(":utils:core-utils"))

    implementation("com.atlassian.jira:jira-rest-java-client-api:$jiraRestClientVersion")
    implementation("com.atlassian.jira:jira-rest-java-client-app:$jiraRestClientVersion") {
        exclude("org.slf4j", "slf4j-log4j12")
    }
    implementation("org.apache.commons:commons-email:$apacheCommonsEmailVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")

    testImplementation("com.github.tomakehurst:wiremock-jre8:$wiremockVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
}
