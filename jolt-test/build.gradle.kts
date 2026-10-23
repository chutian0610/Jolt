dependencies {
    implementation(project(":jolt-core"))
    implementation(project(":jolt-tree"))
    implementation(project(":jolt-bind"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    // T8 differential testing: the independent implementation Jolt is compared against.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
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

// The plain `test` task stays offline: the suite is excluded by tag and the corpus is NOT
// downloaded for it (no gh/network dependency for a plain ./gradlew build).
tasks.test {
    useJUnitPlatform {
        excludeTags("json-test-suite")
    }
}

tasks.register<Test>("jsonTestSuiteTest") {
    description = "Full JSONTestSuite run (y/n/i) — part of check; hard gate since T2."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn(downloadJsonTestSuite)
    useJUnitPlatform {
        includeTags("json-test-suite")
    }
    systemProperty("jolt.jsontestsuite.dir", jsonTestSuiteDir.get().asFile.absolutePath)
}

// The suite is a gate now: every check (and therefore every CI build) runs the full corpus.
tasks.named("check") {
    dependsOn("jsonTestSuiteTest")
}
