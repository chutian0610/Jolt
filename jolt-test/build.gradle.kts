dependencies {
    implementation(project(":jolt-core"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// JSONTestSuite corpus (nst/JSONTestSuite), pinned to commit 1ef36fa01286573e846ac449e8683f8833c5b26a
// so every machine and CI run tests the exact same bytes. Downloaded via `gh api` (direct codeload
// access is unreliable from CN networks; `gh` ships on GitHub runners and is part of this repo's
// documented toolchain). Only the test_parsing/ subtree is unpacked; the zip is cached in the build
// directory and never committed.
val jsonTestSuiteDir = layout.buildDirectory.dir("json-test-suite")
val jsonTestSuiteZip = layout.buildDirectory.file("jsontestsuite.zip")

val downloadJsonTestSuite by tasks.registering {
    val sha = "1ef36fa01286573e846ac449e8683f8833c5b26a"
    outputs.dir(jsonTestSuiteDir)
    outputs.file(jsonTestSuiteZip)
    doLast {
        val zipFile = jsonTestSuiteZip.get().asFile
        if (!zipFile.exists()) {
            zipFile.outputStream().use { out ->
                exec {
                    commandLine("gh", "api", "repos/nst/JSONTestSuite/zipball/$sha")
                    standardOutput = out
                }
            }
        }
        copy {
            from(zipTree(zipFile)) {
                include("*/test_parsing/**")
            }
            eachFile {
                // Strip the "<owner>-<repo>-<sha>/test_parsing/" prefix.
                relativePath = RelativePath(true, *relativePath.segments.drop(2).toTypedArray())
            }
            into(jsonTestSuiteDir)
        }
    }
}

tasks.test {
    // The suite is wired but gated behind its own tag: the minimal T1 lexer cannot pass it yet.
    // `./gradlew build` stays green; `./gradlew :jolt-test:jsonTestSuiteTest` runs the full
    // suite (ignoreFailures until T2 makes it green).
    dependsOn(downloadJsonTestSuite)
    useJUnitPlatform {
        excludeTags("json-test-suite")
    }
    systemProperty("jolt.jsontestsuite.dir", jsonTestSuiteDir.get().asFile.absolutePath)
}

tasks.register<Test>("jsonTestSuiteTest") {
    description = "Full JSONTestSuite run (y/n/i). Allowed to fail until T2 lands."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn(downloadJsonTestSuite)
    useJUnitPlatform {
        includeTags("json-test-suite")
    }
    systemProperty("jolt.jsontestsuite.dir", jsonTestSuiteDir.get().asFile.absolutePath)
    ignoreFailures = true
}
