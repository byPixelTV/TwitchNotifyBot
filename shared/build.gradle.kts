plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    id("com.gradleup.shadow") version libs.versions.shadow.get()
}

group = "dev.bypixel"
version = rootProject.version

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://m2.dv8tion.net/releases")
}

dependencies {
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.reactive)
    implementation(libs.coroutines)
    implementation(libs.json)
    implementation(libs.slf4j)
    implementation(libs.logback)
    implementation(libs.jedis)
    implementation(libs.dotenv)
}