plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization") version "2.4.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// Fat jar with the CLI entry point, used by the Python eval harness.
val cliJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds engine-cli.jar (fat jar) for the eval harness"
    archiveFileName.set("engine-cli.jar")
    manifest { attributes["Main-Class"] = "dev.sloptype.engine.cli.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
}
