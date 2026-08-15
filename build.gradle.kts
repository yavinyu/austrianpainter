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

loom {
    runs {
        named("client") {
            // The stand-in yarn mappings make loom launch the client in the "named" namespace, but
            // an unobfuscated Minecraft means every mod ships its class tweakers in "official" -
            // loader then rejects each one with "Namespace (official) does not match current
            // runtime namespace (named)". The two namespaces hold identical names here, so telling
            // loader which one it is really in is enough.
            vmArg("-Dfabric.runtimeMappingNamespace=official")

            // DevLogin logs the dev client into a real Microsoft account, which an online-mode
            // server (Hypixel, or a sim server with online-mode on) requires. The first launch
            // prints a microsoft.com/link code to the console and waits for you to enter it;
            // credentials are then cached in ~/.devlogin/accounts.json, never in this repo.
            //
            // The process main is always fabric's dev launch injector, and `mainClass` is what it
            // hands off to (loom passes it as fabric.dli.main). So DevLogin slots in between the
            // injector and the game - injector -> DevLogin -> KnotClient - appending the
            // credentials it fetched to the client's arguments.
            mainClass.set("net.covers1624.devlogin.DevLogin")
            programArgs("--launch_target", "net.fabricmc.loader.impl.launch.knot.KnotClient")

            // Hot swapping. Stock JVMs only allow swapping method bodies; the JetBrains Runtime
            // ships DCEVM, which also takes added or removed methods and fields. Pair it with
            // mixin's own hot swap (the agent is attached below) so mixin classes reload too.
            // Only useful when the client is started from a debugger - see runClient's launcher.
            vmArg("-XX:+AllowEnhancedClassRedefinition")
            vmArg("-Dmixin.hotSwap=true")
        }
    }
}

repositories {
    // Only needed for `--offline` builds: Gradle will not serve org.lwjgl:lwjgl:3.4.1:unsafe from
    // its own cache in offline mode. Online builds resolve it normally and ignore this.
    flatDir { dirs("${rootDir}/.offline-libs") }

    // DevLogin, so the dev client can log in with a real account and join online-mode servers.
    maven("https://maven.covers1624.net/")

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
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    // Minecraft ships unobfuscated from 26.x on, so nothing is actually remapped and yarn has
    // published nothing past 1.21.11. Loom refuses to configure without a mappings entry, so this
    // is a stand-in and nothing more - hence the "not built for this version" warning on every
    // build, and the runtime namespace override on the client run below.
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")

    // The loader stays `modImplementation`: that is how loom learns to put the loader's own
    // libraries - ASM, mixin, access-widener - on the dev run classpath. Plain `implementation`
    // still compiles and jars fine, but leaves runClient dying on "ASM not detected on the
    // classpath". Loom does not remap the loader itself, so this costs nothing.
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")

    // The mods are plain `implementation`, not `modImplementation`: on an unobfuscated Minecraft
    // there is nothing to remap, and asking loom to remap a mod drags its sources jar through
    // Mercury, which cannot start without an "official" mapping namespace yarn no longer provides.
    // In dev the loader still finds them by scanning the classpath for fabric.mod.json.
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    // Loom only puts mixin on the classpath for `modImplementation` loader deps, so declare it.
    // Provided by the loader at runtime, never bundled.
    compileOnly("net.fabricmc:sponge-mixin:${project.property("mixin_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // Settings screen. Hard dependency.
    implementation("dev.isxander:yet-another-config-lib:${project.property("yacl_version")}")

    // NanoVG, the 2D renderer com.maxisch.render.render2d draws through. Minecraft already puts the
    // LWJGL core and its natives on the classpath at this exact version, so only the nanovg module
    // is added - a second core would be a conflict, not an addition, hence isTransitive = false.
    //
    // `include` as well as `implementation`, unlike every other dependency here: Minecraft ships no
    // nanovg at all, so a jar built with only `implementation` runs in dev (where Gradle supplies
    // it) and throws NoClassDefFoundError for every actual user. Loom nests these as jar-in-jar and
    // the loader puts them on the classpath, where LWJGL's SharedLibraryLoader finds the natives.
    //
    // Plain `implementation` rather than `modImplementation` for a different reason than the mods
    // above: this is not a mod and not obfuscated, so there is nothing for loom to remap either way.
    //
    // The classifiers are exactly the ones Minecraft ships for its own LWJGL modules, so the mod
    // runs anywhere vanilla does.
    val lwjglVersion = project.property("lwjgl_version") as String
    val lwjglNatives = listOf(
        "natives-windows", "natives-windows-arm64", "natives-windows-x86",
        "natives-linux", "natives-macos", "natives-macos-arm64",
    )

    implementation("org.lwjgl:lwjgl-nanovg:$lwjglVersion") { isTransitive = false }
    include("org.lwjgl:lwjgl-nanovg:$lwjglVersion") { isTransitive = false }

    lwjglNatives.forEach { classifier ->
        implementation("org.lwjgl:lwjgl-nanovg:$lwjglVersion:$classifier") { isTransitive = false }
        include("org.lwjgl:lwjgl-nanovg:$lwjglVersion:$classifier") { isTransitive = false }
    }

    // Only needed to compile the ModMenuApi entrypoint; the mod runs fine without it installed.
    compileOnly("com.terraformersmc:modmenu:${project.property("modmenu_version")}")
    localRuntime("com.terraformersmc:modmenu:${project.property("modmenu_version")}")

    // Dev-run only, never shipped: logs the dev client into a real Microsoft account so it can join
    // online-mode servers. See the client run config for how it is hooked up.
    //
    // Not DevAuth: that one has been unmaintained since 2022 and its OAuth app no longer accepts
    // its own redirect URI, so Microsoft rejects every login with "The provided value for the input
    // parameter 'redirect_uri' is not valid". DevLogin uses the device-code flow, which has no
    // redirect URI to go stale.
    localRuntime("net.covers1624:DevLogin:${project.property("devlogin_version")}")

    // Tests cover only the pure logic that needs no Minecraft registry bootstrap - see src/test.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// LWJGL refuses to load when a module binding and the core disagree on version, and Minecraft is
// what supplies the core - so a Minecraft bump that moves LWJGL silently invalidates lwjgl_version
// without breaking anything until a user opens the paint UI. Resolving the classpath at
// configuration time would be circular, so this checks at execution time and hangs off `check`.
val checkLwjglVersion = tasks.register("checkLwjglVersion") {
    val expected = project.property("lwjgl_version") as String
    val compileClasspath = configurations.compileClasspath
    doLast {
        val core = compileClasspath.get().resolvedConfiguration.lenientConfiguration
            .allModuleDependencies
            .firstOrNull { it.moduleGroup == "org.lwjgl" && it.moduleName == "lwjgl" }
            ?: error("org.lwjgl:lwjgl is not on the compile classpath - did Minecraft stop shipping it?")
        check(core.moduleVersion == expected) {
            "lwjgl_version=$expected but Minecraft ships org.lwjgl:lwjgl:${core.moduleVersion}. " +
                "Set lwjgl_version=${core.moduleVersion} in gradle.properties."
        }
    }
}

tasks.named("check") { dependsOn(checkLwjglVersion) }

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

// Hot swapping needs the JetBrains Runtime rather than the JDK the rest of the build uses: only it
// carries DCEVM. Gradle finds it by scanning ~/.jdks, where IntelliJ also keeps its JDKs.
//
// This only covers `gradlew runClient`. HotSwap itself needs a debugger, so the workflow that
// actually benefits is IntelliJ's generated "Minecraft Client" config run in debug mode - set that
// config's JRE to the same JetBrains Runtime by hand, then Ctrl+Shift+F9 to push a change in.
val javaToolchainService = extensions.getByType<JavaToolchainService>()

tasks.named<JavaExec>("runClient") {
    javaLauncher.set(
        javaToolchainService.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
            vendor.set(JvmVendorSpec.JETBRAINS)
        },
    )

    // Mixin's hot swap agent, so reapplying a mixin does not need a restart either. Resolved from
    // the compile classpath because the version is loom's to pick, not ours.
    doFirst {
        val mixinJar = configurations.compileClasspath.get()
            .firstOrNull { it.name.startsWith("sponge-mixin-") && it.extension == "jar" }
        if (mixinJar != null) jvmArgs("-javaagent:${mixinJar.absolutePath}")
        else logger.warn("sponge-mixin jar not found; mixin hot swap will be inactive")
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
    from("LICENSE.txt") {
        rename { "LICENSE_${project.base.archivesName.get()}" }
    }
    // The bundled NanoVG renderer is BSD-3-Clause and the bundled fonts are OFL; both licences
    // require their notice to travel with a binary redistribution, and the jar is one.
    from("THIRD-PARTY-NOTICES.txt") {
        rename { "THIRD-PARTY-NOTICES_${project.base.archivesName.get()}.txt" }
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

    // Nothing publishes anywhere yet; add a target here if that ever changes. This block is not the
    // same as the top-level `repositories` - that one is where dependencies come from.
    repositories {
    }
}
