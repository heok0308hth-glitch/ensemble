plugins { id("org.jetbrains.kotlin.jvm"); application }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies { implementation(project(":ensemble")) }
application { mainClass.set("MainKt"); applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8") }
tasks.named<JavaExec>("run") { workingDir = rootDir; standardInput = System.`in`; jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8") }
