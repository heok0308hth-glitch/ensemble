plugins { id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false }

// JitPack passes the tag being built (e.g. v0.1.0) in $VERSION; elsewhere gradle.properties decides.
val releaseVersion = System.getenv("VERSION")?.takeIf { System.getenv("JITPACK") == "true" } ?: providers.gradleProperty("VERSION").get()

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = releaseVersion
}
