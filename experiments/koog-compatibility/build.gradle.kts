plugins { kotlin("jvm") version "2.3.10" }
repositories { mavenCentral() }
dependencies {
    implementation("ai.koog:prompt-executor-deepseek-client:1.3.0-beta")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
