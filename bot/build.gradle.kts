plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    id("com.gradleup.shadow") version libs.versions.shadow.get()
    application
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

repositories {
    mavenCentral()

    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://m2.dv8tion.net/releases")
}

application {
    mainClass.set("dev.bypixel.twitchnotify.bot.TwitchNotifyBot")
}

group = "dev.bypixel"
version = rootProject.version

dependencies {
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.reactive)
    implementation(libs.coroutines)
    implementation(libs.json)
    implementation(libs.slf4j)
    implementation(libs.logback)
    implementation(libs.twitch4j)
    implementation(libs.mongodb.driver.coroutine)
    implementation(libs.mongodb.bson.kotlinx)
    implementation(libs.apache.commons.text)
    implementation(libs.jedis)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.discord.webhook)
    implementation(libs.dotenv)
    implementation(libs.bot.commands)
    implementation(libs.jda) {
        exclude(module = "opus-java")
    }
    implementation(libs.stacktrace.decoroutinator)
    implementation(libs.pagination)
    implementation(project(":shared"))
}