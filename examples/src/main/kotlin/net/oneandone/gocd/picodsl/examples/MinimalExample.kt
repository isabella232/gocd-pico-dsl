/**
 * Copyright 1&1 Internet AG, https://github.com/1and1/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.oneandone.gocd.picodsl.examples

import mu.KotlinLogging
import net.oneandone.gocd.picodsl.ConfigSuite
import net.oneandone.gocd.picodsl.dsl.Template
import net.oneandone.gocd.picodsl.dsl.gocd
import java.io.File

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val gocd = gocd {
        pipelines {
            sequence {
                group("dev") {
                    pipeline("deploy") {
                        materials {
                            repoPackage("myArtifact")
                        }
                        template = Template("deploy", "last-stage")
                    }
                }
            }
        }
    }

    val outputFolder = if (args.isNotEmpty()) args[0] else "target/gocd-config"
    val yamlFiles = ConfigSuite(gocd, outputFolder = File(outputFolder)).writeYamlFiles()

    logger.info { "Generated yamlFiles: $yamlFiles" }
}