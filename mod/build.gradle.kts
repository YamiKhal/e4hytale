import java.util.zip.ZipFile

val hytaleVersion = providers.gradleProperty("hytale_version").get()
val hytalePatchline = providers.gradleProperty("hytale_patchline").getOrElse("release")

dependencies {
    implementation(project(":common"))
    // The server jar is provided at runtime by Hytale.
    compileOnly("com.hypixel.hytale:Server:$hytaleVersion")
}

base {
    archivesName = "e4hytale"
}

tasks.processResources {
    val replaceProperties = mapOf(
        "plugin_group" to providers.gradleProperty("plugin_group").get(),
        "plugin_version" to project.version.toString(),
        "server_version" to providers.gradleProperty("server_version").get(),
        "plugin_description" to providers.gradleProperty("plugin_description").get(),
        "plugin_website" to providers.gradleProperty("plugin_website").getOrElse(""),
        "plugin_main_entrypoint" to providers.gradleProperty("plugin_main_entrypoint").get(),
        "plugin_author" to providers.gradleProperty("plugin_author").get(),
    )
    inputs.properties(replaceProperties)
    filesMatching("manifest.json") {
        expand(replaceProperties)
    }
}

// the plugin jar carries the :common classes, Hytale loads one jar per mod
tasks.jar {
    dependsOn(":common:jar")
    from(project(":common").sourceSets.main.map { it.output })
    manifest {
        attributes(
            "Implementation-Title" to "e4hytale",
            "Implementation-Version" to project.version,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Local Hytale install integration
//
//   gradlew :mod:deployClient   -> build + copy the jar into the game's UserData/Mods (hosted worlds)
//   gradlew :mod:runServer      -> build + run a dedicated server from mod/run with the mod loaded
//   gradlew :mod:hytaleInfo     -> print the resolved install paths and versions
// ---------------------------------------------------------------------------------------------

val hytaleHome: File = providers.gradleProperty("hytale_home").orNull?.let(::File)
    ?: System.getenv("APPDATA")?.let { File(it, "Hytale") }
    ?: File(System.getProperty("user.home"), ".hytale")
val hytalePackage = File(hytaleHome, "install/$hytalePatchline/package")
val hytaleServerJar = File(hytalePackage, "game/latest/Server/HytaleServer.jar")
val hytaleAssetsZip = File(hytalePackage, "game/latest/Assets.zip")
val hytaleJava = File(hytalePackage, "jre/latest/bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
val hytaleClientMods = File(hytaleHome, "UserData/Mods")
val runDir = layout.projectDirectory.dir("run")
val modJar = tasks.jar.flatMap { it.archiveFile }

fun installedServerVersion(): String? = runCatching {
    ZipFile(hytaleServerJar).use { zip ->
        zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).bufferedReader().readLines()
            .firstOrNull { it.startsWith("Implementation-Version:") }
            ?.substringAfter(':')?.trim()
    }
}.getOrNull()

fun requireInstall(logger: org.gradle.api.logging.Logger) {
    check(hytaleServerJar.isFile) { "Hytale server not found at $hytaleServerJar. Install the game or set hytale_home / hytale_patchline." }
    check(hytaleAssetsZip.isFile) { "Hytale assets not found at $hytaleAssetsZip." }
    check(hytaleJava.isFile) { "Hytale bundled Java not found at $hytaleJava." }
    val installed = installedServerVersion()
    if (installed != null && installed != hytaleVersion) {
        logger.warn("WARNING: compiling against Server $hytaleVersion but the installed $hytalePatchline game is $installed. Update hytale_version in gradle.properties.")
    }
}

val hytaleInfo by tasks.registering {
    group = "hytale"
    description = "Prints the resolved Hytale install paths and versions."
    doLast {
        println("Hytale home      : $hytaleHome")
        println("Patchline        : $hytalePatchline")
        println("Server jar       : $hytaleServerJar (${installedServerVersion() ?: "missing"})")
        println("Compile version  : $hytaleVersion")
        println("Client mods dir  : $hytaleClientMods")
        println("Dev server dir   : ${runDir.asFile}")
    }
}

val deployClient by tasks.registering(Copy::class) {
    group = "hytale"
    description = "Builds the mod and installs it into the game's UserData/Mods folder (replacing older builds)."
    dependsOn(tasks.jar)
    from(modJar)
    into(hytaleClientMods)
    doFirst {
        requireInstall(logger)
        hytaleClientMods.listFiles { f -> f.name.startsWith("e4hytale-") && f.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }
}

val prepareRunDir by tasks.registering(Copy::class) {
    group = "hytale"
    description = "Copies the freshly built mod into mod/run/mods (removing older builds)."
    dependsOn(tasks.jar)
    from(modJar)
    into(runDir.dir("mods"))
    doFirst {
        runDir.dir("mods").asFile.listFiles { f -> f.name.startsWith("e4hytale-") && f.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }
}

val runServer by tasks.registering(Exec::class) {
    group = "hytale"
    description = "Runs a local dedicated server from mod/run with the mod. First run: '/auth login device' then '/auth persistence Encrypted'."
    dependsOn(prepareRunDir)
    workingDir = runDir.asFile
    standardInput = System.`in`
    isIgnoreExitValue = true
    doFirst {
        requireInstall(logger)
        commandLine(
            hytaleJava.absolutePath, "-Xms1G", "-Xmx4G",
            "-jar", hytaleServerJar.absolutePath,
            "--assets", hytaleAssetsZip.absolutePath,
            "--disable-sentry",
            "--allow-op",
        )
    }
}
