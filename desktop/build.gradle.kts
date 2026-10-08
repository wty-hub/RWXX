import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import edu.sc.seis.launch4j.tasks.Launch4jLibraryTask
import io.github.rwx.build.AssetListGenerationSupport
import io.github.rwx.build.KoolVulkanOverlayPatchTask
import java.util.*

plugins {
    id("application")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.launch4j)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val lwjglVersion = libs.versions.lwjglVersion.get()
val webrtcJavaVersion = libs.versions.webrtcJavaVersion.get()

enum class DesktopPlatform(
    val id: String,
    val osName: String,
    val arch: String,
    val lwjglClassifier: String,
    val webrtcClassifier: String,
    val iconExtension: String,
) {
    LINUX_X64("linux-x64", "linux", "x64", "natives-linux", "linux-x86_64", "png"),
    LINUX_ARM64("linux-arm64", "linux", "arm64", "natives-linux-arm64", "linux-aarch64", "png"),
    WINDOWS_X64("windows-x64", "windows", "x64", "natives-windows", "windows-x86_64", "ico"),
    MACOS_X64("macos-x64", "macos", "x64", "natives-macos", "macos-x86_64", "icns"),
    MACOS_ARM64("macos-arm64", "macos", "arm64", "natives-macos-arm64", "macos-aarch64", "icns"),
    ;

    companion object {
        fun fromId(id: String): DesktopPlatform = entries.firstOrNull { it.id == id }
            ?: throw GradleException(
                "Unsupported desktop platform '$id'. Supported values: ${entries.joinToString { it.id }}",
            )

        fun current(): DesktopPlatform {
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
            val isArm64 = arch.contains("aarch64") || arch.contains("arm64")
            return when {
                os.contains("win") && !isArm64 -> WINDOWS_X64
                os.contains("mac") && isArm64 -> MACOS_ARM64
                os.contains("mac") -> MACOS_X64
                os.contains("linux") && isArm64 -> LINUX_ARM64
                os.contains("linux") -> LINUX_X64
                else -> throw GradleException("Unsupported desktop host: os.name=$os, os.arch=$arch")
            }
        }
    }
}

val hostPlatform = DesktopPlatform.current()
val targetPlatform = providers.gradleProperty("targetPlatform")
    .map(DesktopPlatform::fromId)
    .getOrElse(hostPlatform)
val jpackageVersion = project.version.toString().substringBefore('-').substringBefore('+')
val packagingJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

val koolDesktopPatchSource by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

val universalNatives by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

val assetListGeneration = AssetListGenerationSupport.register(project)

dependencies {
    implementation(project(":mod-api"))
    implementation(project(":core"))
    implementation(libs.httpclient)
    // OGG/Vorbis decoding for the legacy desktop audio path (previously exposed by the Slick module).
    implementation(files("libs/jogg-0.0.7.jar"))
    implementation(files("libs/jorbis-0.0.15.jar"))
    implementation(libs.kool.core.desktop)
    koolDesktopPatchSource(libs.kool.core.desktop)
    implementation(libs.webrtc.java)
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcJavaVersion:${targetPlatform.webrtcClassifier}")
    DesktopPlatform.entries.forEach { platform ->
        universalNatives("dev.onvoid.webrtc:webrtc-java:$webrtcJavaVersion:${platform.webrtcClassifier}")
    }
    implementation(libs.lwjgl3.awt) {
        exclude(group = "org.lwjgl")
    }
    val lwjglModules = listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal", "lwjgl-jawt")
    lwjglModules.forEach { module ->
        implementation("org.lwjgl:$module:$lwjglVersion")
    }
    val lwjglNativeModules = lwjglModules - "lwjgl-jawt"
    lwjglNativeModules.forEach { module ->
        runtimeOnly("org.lwjgl:$module:$lwjglVersion:${targetPlatform.lwjglClassifier}")
        DesktopPlatform.entries.map { it.lwjglClassifier }.distinct().forEach { classifier ->
            universalNatives("org.lwjgl:$module:$lwjglVersion:$classifier")
        }
    }
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    testImplementation(kotlin("test"))
    testImplementation("org.ow2.asm:asm-tree:9.9")
}

val patchKoolVulkanOverlay by tasks.registering(KoolVulkanOverlayPatchTask::class) {
    koolDesktopJar.set(layout.file(provider { koolDesktopPatchSource.singleFile }))
    outputDirectory.set(layout.buildDirectory.dir("generated/kool-vulkan-overlay-patch"))
}

sourceSets.main.get().output.dir(
    mapOf("builtBy" to patchKoolVulkanOverlay),
    patchKoolVulkanOverlay.flatMap { it.outputDirectory },
)

val appName: String by project

application {
    applicationName = appName
    mainClass = "io.github.rwx.KoolDesktopMain"
    applicationDefaultJvmArgs = listOf(
        "-Dorg.lwjgl.opengl.contextAPI=native",
        "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.awt.im=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    ) + if (targetPlatform.osName == "macos") listOf("--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED") else emptyList()
}

tasks.withType<Test>().configureEach {
    if (hostPlatform.osName == "macos") {
        jvmArgs("--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED")
    }
}

distributions {
    configureEach {
        contents {
            from(rootProject.layout.projectDirectory.dir("assets")) {
                into("assets")
                exclude(AssetListGenerationSupport.runtimeAssetExcludes)
            }
        }
    }
}

tasks.named<Copy>("processResources") {
    dependsOn(assetListGeneration.task)
}

tasks.named<JavaExec>("run") {
    workingDir = project.file("..")
    configureKoolRenderBackend()
    configureRunArgs()
}

tasks.register<JavaExec>("headless") {
    group = "verification"
    description = "Run a replay or single-player map without a window. Pass options with --args."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.HeadlessMain")
    workingDir = project.file("..")
    jvmArgs("-Djava.awt.headless=true")
}

/**
 * Pixel oracle for the GPU map cell path (`RenderTargetMode.GPU_TARGET`). Readback waits for the
 * queue, so this is a functional check and must never be used for performance measurement.
 */
tasks.register<JavaExec>("runGpuMapCellOracle") {
    group = "verification"
    description = "Runs the standalone Vulkan check for GPU offscreen map cell targets."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.kool.vulkan.KoolCanvasGpuMapCellOracle")
    workingDir = project.file("..")
    environment("RWX_GPU_MAP_CELL_TARGETS", "1")
    environment("RWX_RUN_VULKAN_GPU_MAP_CELL_ORACLE", "1")
    jvmArgs(
        "-Dorg.lwjgl.opengl.contextAPI=native",
        "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
}

/**
 * Colour-correctness gate for the native BGRA upload path.
 *
 * That path uploads texture pixels as BGRA instead of converting them on the CPU, and it is off by
 * default. Measured under pan+zoom it is worth about 9% frame rate and 13% off the P99 present
 * interval, which is why it should not stay off on a suspicion - but a channel-order mistake would be a
 * visible colour bug that no frame-rate number can catch, so it needs its own pixel oracle.
 */
tasks.register<JavaExec>("runBgraSamplingOracle") {
    group = "verification"
    description = "Runs the standalone Vulkan BGRA sampling check for the native BGRA upload path."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.kool.vulkan.VulkanBgraSamplingOracle")
    workingDir = project.file("..")
    environment("RWX_NATIVE_BGRA_UPLOAD", "1")
    environment("RWX_RUN_VULKAN_BGRA_ORACLE", "1")
    jvmArgs(
        "-Dorg.lwjgl.opengl.contextAPI=native",
        "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
}

tasks.register<JavaExec>("runTextGlyphOracle") {
    group = "verification"
    description = "Compares instanced glyphs with production MSDF text on Vulkan, including clipping and ordering."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.kool.vulkan.KoolCanvasGpuMapCellOracle")
    workingDir = project.file("..")
    environment("RWX_GPU_MAP_CELL_TARGETS", "1")
    environment("RWX_RUN_VULKAN_GPU_MAP_CELL_ORACLE", "1")
    environment("RWX_GPU_TEXT_ORACLE", "1")
    jvmArgs("-Dorg.lwjgl.opengl.contextAPI=native", "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

tasks.register<JavaExec>("runTextLabelOracle") {
    group = "verification"
    description = "Compares immutable whole-label geometry generations with production MSDF text on Vulkan."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.kool.vulkan.KoolCanvasGpuMapCellOracle")
    workingDir = project.file("..")
    environment("RWX_GPU_MAP_CELL_TARGETS", "1")
    environment("RWX_RUN_VULKAN_GPU_MAP_CELL_ORACLE", "1")
    environment("RWX_GPU_TEXT_ORACLE", "1")
    environment("RWX_GPU_TEXT_LABEL_ORACLE", "1")
    jvmArgs("-Dorg.lwjgl.opengl.contextAPI=native", "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

tasks.register<JavaExec>("runSpriteAppendOracle") {
    group = "verification"
    description = "Compares incremental Vulkan sprite atlas appends against individual textures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.kool.vulkan.KoolCanvasGpuMapCellOracle")
    workingDir = project.file("..")
    environment("RWX_GPU_MAP_CELL_TARGETS", "1")
    environment("RWX_RUN_VULKAN_GPU_MAP_CELL_ORACLE", "1")
    environment("RWX_GPU_SPRITE_APPEND_ORACLE", "1")
    environment("RWX_INCREMENTAL_SPRITE_ATLAS", "1")
    environment("RWX_PROJECTED_SPRITE_ATLAS", "1")
    jvmArgs("-Dorg.lwjgl.opengl.contextAPI=native", "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

tasks.register<JavaExec>("runMapGridOracle") {
    group = "verification"
    description = "Compares actual rolling map grids through pan, zoom, edges, fog and atlas updates on Vulkan."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.kool.vulkan.KoolCanvasGpuMapCellOracle")
    workingDir = project.file("..")
    environment("RWX_GPU_MAP_CELL_TARGETS", "1")
    environment("RWX_RUN_VULKAN_GPU_MAP_CELL_ORACLE", "1")
    environment("RWX_REAL_MAP_CELL_ORACLE", "1")
    environment("RWX_MAP_TEXTURE_BATCHES", "1")
    environment("RWX_MAP_FOG_BATCHES", "1")
    environment("RWX_GPU_MAP_GRID_ORACLE", "1")
    jvmArgs("-Dorg.lwjgl.opengl.contextAPI=native", "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

tasks.register<JavaExec>("runRealMapCellOracle") {
    group = "verification"
    description = "Checks real MapLayer tiles and changing atlas versions through the Vulkan map-cell batch path."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.kool.vulkan.KoolCanvasGpuMapCellOracle")
    workingDir = project.file("..")
    environment("RWX_GPU_MAP_CELL_TARGETS", "1")
    environment("RWX_RUN_VULKAN_GPU_MAP_CELL_ORACLE", "1")
    environment("RWX_REAL_MAP_CELL_ORACLE", "1")
    environment("RWX_MAP_TEXTURE_BATCHES", "1")
    environment("RWX_MAP_FOG_BATCHES", "1")
    environment("RWX_GPU_FOG_ORACLE", "1")
    environment("RWX_GPU_TEXT_SHADER_ORACLE", "1")
    jvmArgs("-Dorg.lwjgl.opengl.contextAPI=native", "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

/**
 * Control for the GPU map cell check: the pre-existing sprite atlas oracle. If this also fails on the
 * current driver, an offscreen pass in a scene is the problem rather than the map cell code. */
tasks.register<JavaExec>("runSpriteAtlasOracle") {
    group = "verification"
    description = "Runs the pre-existing standalone Vulkan sprite atlas oracle as a driver control."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rwx.kool.vulkan.VulkanSpriteAtlasOracle")
    workingDir = project.file("..")
    environment("RWX_RUN_VULKAN_SPRITE_ATLAS_ORACLE", "1")
    jvmArgs(
        "-Dorg.lwjgl.opengl.contextAPI=native",
        "-Dorg.lwjgl.system.stackSize=512",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
}

tasks.named("runShadow") {
    group = null
    enabled = false
}

fun ShadowJar.configureRunnableJar() {
    dependsOn(assetListGeneration.task)
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true
    mergeServiceFiles()
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Add-Exports"] = "java.desktop/com.apple.eawt"
    }
    from(rootProject.layout.projectDirectory.dir("assets")) {
        into("assets")
        exclude(AssetListGenerationSupport.runtimeAssetExcludes)
    }
}

fun ShadowJar.excludeNonTargetNativeResources(platform: DesktopPlatform) {
    DesktopPlatform.entries
        .map(DesktopPlatform::osName)
        .distinct()
        .filterNot { it == platform.osName }
        .forEach { otherOs ->
            exclude("$otherOs/**")
            exclude("META-INF/$otherOs/**")
        }

    when (platform) {
        DesktopPlatform.LINUX_X64 -> {
            exclude("linux/arm32/**", "linux/arm64/**", "linux/ppc64le/**", "linux/riscv64/**")
            exclude("META-INF/linux/arm32/**", "META-INF/linux/arm64/**")
            exclude("META-INF/linux/ppc64le/**", "META-INF/linux/riscv64/**")
        }

        DesktopPlatform.LINUX_ARM64 -> {
            exclude("linux/arm32/**", "linux/x64/**", "linux/ppc64le/**", "linux/riscv64/**")
            exclude("META-INF/linux/arm32/**", "META-INF/linux/x64/**")
            exclude("META-INF/linux/ppc64le/**", "META-INF/linux/riscv64/**")
        }

        DesktopPlatform.MACOS_X64 -> {
            exclude("macos/arm64/**")
            exclude("META-INF/macos/arm64/**")
        }

        DesktopPlatform.MACOS_ARM64 -> {
            exclude("macos/x64/**")
            exclude("META-INF/macos/x64/**")
        }

        DesktopPlatform.WINDOWS_X64 -> Unit
    }
}

tasks.named<ShadowJar>("shadowJar") {
    group = "build"
    description = "Builds a runnable desktop fat jar with runtime assets and natives for all supported desktop platforms."
    archiveBaseName = appName
    archiveClassifier = "all"
    configurations = listOf(project.configurations.runtimeClasspath.get(), universalNatives)
    configureRunnableJar()
}

val platformFatJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "Builds a runnable fat jar for ${targetPlatform.id}."
    archiveBaseName = appName
    archiveClassifier = targetPlatform.id
    configurations = listOf(project.configurations.runtimeClasspath.get())
    configureRunnableJar()
    excludeNonTargetNativeResources(targetPlatform)
}

tasks.register("multiPlatformFatJar") {
    group = "build"
    description =
        "Builds build/libs/$appName-${project.version}-all.jar with runtime assets for all supported desktop platforms."
    dependsOn(tasks.named("shadowJar"))
}


val jpackageInputDir = layout.buildDirectory.dir("jpackage/input/${targetPlatform.id}")
val jpackageImageDir = layout.buildDirectory.dir("jpackage/image/${targetPlatform.id}")
val generatedMacIcon = layout.buildDirectory.file("generated-icons/logo.icns")

val generateMacIcon by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Generates the macOS ICNS app icon with iconutil."
    val iconSet = layout.projectDirectory.dir("src/main/resources/icons/logo.iconset")
    inputs.dir(iconSet)
    outputs.file(generatedMacIcon)
    doFirst {
        generatedMacIcon.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "iconutil",
        "--convert", "icns",
        "--output", generatedMacIcon.get().asFile.absolutePath,
        iconSet.asFile.absolutePath,
    )
}

val stageJpackageInput by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the ${targetPlatform.id} fat jar for jpackage."
    dependsOn(platformFatJar)
    from(platformFatJar)
    into(jpackageInputDir)
}

val createJpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Creates a jpackage app image for ${targetPlatform.id}."
    dependsOn(stageJpackageInput)
    inputs.dir(jpackageInputDir)
    outputs.dir(jpackageImageDir)
    if (targetPlatform.osName == "macos") {
        dependsOn(generateMacIcon)
    }

    doFirst {
        if (hostPlatform != targetPlatform) {
            throw GradleException(
                "jpackage cannot cross-package ${targetPlatform.id} on ${hostPlatform.id}. " +
                        "Run this task on a ${targetPlatform.id} host.",
            )
        }

        delete(jpackageImageDir.get().asFile)
        val executableName = if (hostPlatform.osName == "windows") "jpackage.exe" else "jpackage"
        val executable = packagingJava.get().metadata.installationPath.file("bin/$executableName").asFile
        if (!executable.isFile) {
            throw GradleException("jpackage was not found in ${packagingJava.get().metadata.installationPath}")
        }

        val args = mutableListOf(
            "--type", "app-image",
            "--name", appName,
            "--app-version", jpackageVersion,
            "--vendor", "RWXX",
            "--description", "Cross-platform real-time strategy game",
            "--dest", jpackageImageDir.get().asFile.absolutePath,
            "--input", jpackageInputDir.get().asFile.absolutePath,
            "--main-jar", platformFatJar.get().archiveFileName.get(),
            "--main-class", application.mainClass.get(),
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dorg.lwjgl.opengl.contextAPI=native",
            "--java-options", "-Dorg.lwjgl.system.stackSize=512",
            "--java-options", "-Dlaunch.dir=\$ROOTDIR",
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--java-options", "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--java-options", "--add-opens=java.desktop/sun.awt.im=ALL-UNNAMED",
            "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED",
        )
        val icon = if (targetPlatform.osName == "macos") {
            args += listOf("--java-options", "--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED")
            generatedMacIcon.get().asFile
        } else {
            layout.projectDirectory.file("src/main/resources/icons/logo.${targetPlatform.iconExtension}").asFile
        }
        if (icon.isFile) {
            args += listOf("--icon", icon.absolutePath)
        }
        commandLine(executable.absolutePath, *args.toTypedArray())
    }
}

val packagedAppName = if (targetPlatform.osName == "macos") "$appName.app" else appName

if (targetPlatform.osName == "windows") {
    val createWindowsLauncher = tasks.named<Launch4jLibraryTask>("createExe") {
        group = "distribution"
        description = "Creates a Windows launcher that always uses the bundled runtime."
        setJarTask(platformFatJar.get())
        outputDir.set("launch4j/windows")
        outfile.set("$appName.exe")
        mainClassName.set(application.mainClass)
        dontWrapJar.set(true)
        libraryDir.set("app")
        bundledJrePath.set("runtime")
        requires64Bit.set(true)
        jreMinVersion.set("25")
        icon.set(layout.projectDirectory.file("src/main/resources/icons/logo.ico").asFile.absolutePath)
        jvmOptions.set(
            listOf(
                "-Djpackage.app-version=$jpackageVersion",
                "-Dfile.encoding=UTF-8",
                "-Dorg.lwjgl.opengl.contextAPI=native",
                "-Dorg.lwjgl.system.stackSize=512",
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt.im=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
            ),
        )
        productName.set(appName)
        fileDescription.set("Cross-platform real-time strategy game")
    }

    val installWindowsLauncher by tasks.registering(Copy::class) {
        group = "distribution"
        description = "Replaces the jpackage launcher with the bundled-runtime Windows launcher."
        dependsOn(createJpackageImage, createWindowsLauncher)
        from(layout.buildDirectory.file("launch4j/windows/$appName.exe"))
        from(packagingJava.map { it.metadata.installationPath.file("bin/javaw.exe") }) {
            into("runtime/bin")
        }
        into(jpackageImageDir.map { it.dir(packagedAppName) })
        doFirst {
            val launcher = jpackageImageDir.get().file("$packagedAppName/$appName.exe").asFile
            if (launcher.exists() && !launcher.setWritable(true)) {
                throw GradleException("Could not make the jpackage launcher writable: $launcher")
            }
        }
    }

    tasks.register<Zip>("packageDesktopDistribution") {
        group = "distribution"
        description = "Creates the ${targetPlatform.id} jpackage distribution zip."
        dependsOn(installWindowsLauncher)
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        archiveFileName.set("$appName-${project.version}-${targetPlatform.id}-desktop.zip")
        from(jpackageImageDir.map { it.dir(packagedAppName) }) {
            into(packagedAppName)
        }
    }
} else {
    tasks.register<Exec>("packageDesktopDistribution") {
        group = "distribution"
        description = "Creates the ${targetPlatform.id} jpackage distribution zip."
        dependsOn(createJpackageImage)
        val outputFile = layout.buildDirectory.file(
            "distributions/$appName-${project.version}-${targetPlatform.id}-desktop.zip",
        )
        inputs.dir(jpackageImageDir)
        outputs.file(outputFile)
        workingDir(jpackageImageDir)
        doFirst {
            outputFile.get().asFile.parentFile.mkdirs()
            outputFile.get().asFile.delete()
        }
        commandLine("zip", "-qry", outputFile.get().asFile.absolutePath, packagedAppName)
    }
}

tasks.register<JavaExec>("bakeMsdfFonts") {
    group = "build setup"
    description = "Pre-generates the committed MSDF font atlases under assets/font."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.rwx.MsdfFontBaker"
    workingDir = project.file("..")
}

fun JavaExec.configureKoolRenderBackend() {
    val backend = providers.gradleProperty("rwxKoolBackend")
        .orElse(providers.systemProperty("rwx.kool.backend"))
    if (backend.isPresent) {
        systemProperty("rwx.kool.backend", backend.get())
    }
}

fun JavaExec.configureRunArgs() {
    providers.gradleProperty("rwxRunArgs").orNull
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotBlank() }
        ?.let(::args)
}
