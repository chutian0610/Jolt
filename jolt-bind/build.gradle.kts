plugins {
    id("java-test-fixtures")
}

dependencies {
    implementation(project(":jolt-core"))
    implementation(project(":jolt-tree"))

    testFixturesImplementation(project(":jolt-core"))
    testFixturesImplementation(project(":jolt-tree"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
