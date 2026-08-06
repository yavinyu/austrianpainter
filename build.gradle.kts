import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // Matches the Kotlin bundled by the fabric-language-kotlin version in gradle.properties, so the
    // mod is compiled against exactly what the loader provides at runtime.
    kotlin("jvm") version "2.4.10"
    // 1.16.2 refuses the yarn placeholder below ("expected official but got intermediary"); 1.17
    // is the first line that copes with an unobfuscated Minecraft plus stale mappings.
    id("fabric-loom") version "1.17.18"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}


fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    // Only needed for `--offline` builds: Gradle will not serve org.lwjgl:lwjgl:3.4.1:unsafe from
    // its own cache in offline mode. Online builds resolve it normally and ignore this.
    flatDir { dirs("${rootDir}/.offline-libs") }

    maven {
        name = "Xander"
        url = uri("https://maven.isxander.dev/releases")
    }
    maven {
        name = "Terraformers"
        url = uri("https://maven.terraformersmc.com/releases")
    }

    mavenCentral()
    // Mojang publishes some libraries without POMs, so allow artifact-only metadata.
    maven("https://libraries.minecraft.net/") {
        metadataSources {
            mavenPom()
            artifact()
        }
    }

    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    // Minecraft ships unobfuscated from 26.x on, so nothing is actually remapped and yarn has
    // published nothing past 1.21.11. Loom still insists on a mappings dependency, so this is that
    // and nothing more - hence the "not built for this version" warning on every build.
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")

    // Plain `implementation`, not `modImplementation`: on an unobfuscated Minecraft there is
    // nothing to remap, and asking loom to remap a mod drags its sources jar through Mercury, which
    // cannot start without an "official" mapping namespace that yarn no longer provides.
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    // Loom only puts mixin on the classpath for `modImplementation` loader deps, so declare it.
    // Provided by the loader at runtime, never bundled.
    compileOnly("net.fabricmc:sponge-mixin:${project.property("mixin_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // Settings screen. Hard dependency.
    implementation("dev.isxander:yet-another-config-lib:${project.property("yacl_version")}")

    // Only needed to compile the ModMenuApi entrypoint; the mod runs fine without it installed.
    compileOnly("com.terraformersmc:modmenu:${project.property("modmenu_version")}")
    localRuntime("com.terraformersmc:modmenu:${project.property("modmenu_version")}")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version")!!,
            "loader_version" to project.property("loader_version")!!,
            "kotlin_loader_version" to project.property("kotlin_loader_version")!!
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
