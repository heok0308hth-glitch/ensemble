plugins { id("org.jetbrains.kotlin.jvm"); application }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(project(":ensemble"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")   // virtual time: hours of simulated work in seconds
}
application { mainClass.set("BenchKt") }
tasks.named<JavaExec>("run") { workingDir = rootDir; jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8") }
