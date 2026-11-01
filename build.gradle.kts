plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}
kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir("tooling/src/main/kotlin") }
}
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") }
tasks.register<Jar>("guideTools") {
    dependsOn(tasks.classes)
    archiveFileName.set("guide-tools.jar")
    destinationDirectory.set(layout.projectDirectory.dir("tools"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "dev.achievo.community.CommunityGuideCatalogBuilderKt" }
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
