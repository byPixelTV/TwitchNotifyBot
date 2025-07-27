import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    id("com.gradleup.shadow") version libs.versions.shadow.get()
}

group = "dev.bypixel"
version = "0.0.1"
rootProject.version = version

tasks.named("build") {
    enabled = false
}

tasks.named("shadowJar") {
    enabled = false
}

repositories {
    mavenCentral()
    mavenLocal()

    
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://m2.dv8tion.net/releases")
}

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")

    version = version
    group = "dev.bypixel"

    repositories {
        mavenCentral()
        mavenLocal()
        
        
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://m2.dv8tion.net/releases")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    kotlin {
        jvmToolchain(21)
    }
}

subprojects {
    tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
        archiveBaseName.set(project.name)
        archiveFileName.set("${project.name}.jar")

        destinationDirectory.set(file("${rootProject.layout.buildDirectory.get()}/libs"))
    }

    tasks.withType<Jar> {
        archiveBaseName.set(project.name)
        archiveFileName.set("${project.name}.jar")
    }
}

tasks.register("cleanBuildDirs") {
    group = "build"
    description = "Deletes the root build/libs directory to avoid leftover jars with different commit hashes"
    doFirst {
        val rootLibs = rootProject.layout.buildDirectory.dir("libs").get().asFile
        if (rootLibs.exists()) {
            rootLibs.deleteRecursively()
            println("✅ Deleted root build/libs directory")
        }
    }
}


tasks.register("buildAll") {
    group = "build"
    description = "Builds all valid subprojects using shadowJar"

    dependsOn("cleanBuildDirs")

    dependsOn(
        subprojects.filter { sub ->
            val hasBuildFile = file("${sub.projectDir}/build.gradle.kts").exists()
            val hasPlugin = sub.plugins.hasPlugin("java") || sub.plugins.hasPlugin("org.jetbrains.kotlin.jvm")
            hasBuildFile && hasPlugin
        }.mapNotNull { sub ->
            sub.tasks.findByName("shadowJar")
        }
    )
}