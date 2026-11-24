plugins { id("org.jetbrains.kotlin.jvm"); `java-library`; `maven-publish` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Android ships org.json in the platform: exclude it there with
    // implementation("…:ensemble:…") { exclude(group = "org.json", module = "json") }
    api("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test { testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }

publishing {
    publications {
        create<MavenPublication>("ensemble") {
            artifactId = "ensemble"
            from(components["java"])
            pom {
                name.set("Ensemble")
                description.set("Make several LLMs work as one team: a conductor plans, a load-aware market dispatches, a shared board connects, rivals cross-review. Pure Kotlin, coroutines, no Android dependency.")
                url.set("https://github.com/heok0308hth-glitch/ensemble")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                developers { developer { id.set("heok0308hth-glitch"); name.set("Heo Taehyeok") } }
                scm { url.set("https://github.com/heok0308hth-glitch/ensemble") }
            }
        }
    }
}
