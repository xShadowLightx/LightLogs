import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    java
}

group = "dev.lightlogs"
version = "1.1.2"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version, "apiVersion" to "1.21.11")
    }
}

tasks.jar {
    archiveBaseName.set("LightLogs")
    archiveClassifier.set("paper-1.21.11")
}

// Paper 26.3 runs on Java 25 and has a separate API line. Keep it as a distinct
// source set and jar so the 1.21.11 build stays available on Java 21.
val paper263 by sourceSets.creating {
    java.setSrcDirs(listOf("src/main/java"))
    resources.setSrcDirs(listOf("src/main/resources"))
}

dependencies {
    add(paper263.compileOnlyConfigurationName, "io.papermc.paper:paper-api:26.3.build.+")
}

tasks.named<JavaCompile>(paper263.compileJavaTaskName) {
    javaCompiler = javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    options.release.set(25)
}

tasks.named<ProcessResources>(paper263.processResourcesTaskName) {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version, "apiVersion" to "26.3")
    }
}

tasks.register<Jar>("paper263Jar") {
    group = "build"
    description = "Builds the Paper 26.3 plugin jar (requires JDK 25)."
    archiveBaseName.set("LightLogs")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("paper-26.3")
    from(paper263.output)
    dependsOn(paper263.classesTaskName)
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds both the Paper 1.21.11 and Paper 26.3 plugin jars."
    dependsOn(tasks.build, tasks.named("paper263Jar"))
}
