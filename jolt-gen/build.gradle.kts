dependencies {
    implementation(project(":jolt-core"))
    implementation(project(":jolt-tree"))
    implementation(project(":jolt-bind"))
    implementation("net.bytebuddy:byte-buddy:1.15.10")

    testImplementation(testFixtures(project(":jolt-bind")))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
