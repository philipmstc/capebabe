plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "org.example"
version = "0.0.8"

repositories {
    mavenCentral()
}
tasks.shadowJar {
    manifest {
        attributes("Main-Class" to  "net.shchoo.scafe.scafe.Main")
    }
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("com.googlecode.lanterna:lanterna:3.1.5")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------------------------
// A stand-alone app with Java bundled in, so players don't need Java or Gradle: ./gradlew packageApp
// Built with jpackage from the JDK running Gradle, for the OS you build on (CI builds each OS, see
// .github/workflows/release.yml). Ends up in build/dist:
//   Windows: capebabe-<version>-windows-x64.zip   (capebabe/capebabe.exe inside)
//   macOS:   capebabe-<version>-macos-<arch>.dmg   (unsigned: right-click > Open the first time)
//   Linux:   capebabe-<version>-linux-x64.tar.gz  (capebabe/bin/capebabe)
// ---------------------------------------------------------------------------------------------------------------
val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("win")
val isMac = osName.contains("mac")
val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
val platform = if (isWindows) "windows-$arch" else if (isMac) "macos-$arch" else "linux-$arch"
val jpackageDir = layout.buildDirectory.dir("jpackage")
val distDir = layout.buildDirectory.dir("dist")

val jpackageInput = tasks.register<Sync>("jpackageInput") {
    from(tasks.shadowJar)
    into(jpackageDir.map { it.dir("input") })
}

val jpackage = tasks.register<Exec>("jpackage") {
    description = "Builds the app (an app folder, or a .dmg on macOS) with jpackage."
    dependsOn(jpackageInput)
    val output = jpackageDir.map { it.dir("output") }
    inputs.files(jpackageInput)
    inputs.property("version", version.toString())
    outputs.dir(output)
    doFirst {
        delete(output)
        val tool = File(System.getProperty("java.home"), "bin/" + if (isWindows) "jpackage.exe" else "jpackage")
        // macOS refuses versions starting with 0 for the app bundle; the file name keeps the real version
        val appVersion = if (isMac && version.toString().startsWith("0.")) "1.0.0" else version.toString()
        val args = mutableListOf(tool.path,
            "--type", if (isMac) "dmg" else "app-image",
            "--name", "capebabe",
            "--app-version", appVersion,
            "--vendor", "capebabe",
            "--description", "A RuneScape-inspired terminal number-go-up game",
            "--input", jpackageDir.get().dir("input").asFile.path,
            "--main-jar", tasks.shadowJar.get().archiveFileName.get(),
            "--main-class", "net.shchoo.scafe.scafe.Main",
            "--dest", output.get().asFile.path,
            // jpackage's usual trimming minus --strip-native-commands: hosting needs the bundled keytool (see Tls)
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files")
        if (isMac) {
            args += listOf("--mac-package-name", "capebabe")
        }
        commandLine(args)
    }
}

val packageApp = tasks.register("packageApp") {
    description = "Builds the stand-alone app for this OS into build/dist."
    group = "distribution"
    dependsOn(jpackage)
}

if (isMac) {
    val copyDmg = tasks.register<Copy>("copyDmg") {
        dependsOn(jpackage)
        from(jpackageDir.map { it.dir("output") }) { include("*.dmg") }
        into(distDir)
        rename { "capebabe-${project.version}-$platform.dmg" }
    }
    packageApp.configure { dependsOn(copyDmg) }
} else if (isWindows) {
    val zipApp = tasks.register<Zip>("zipApp") {
        dependsOn(jpackage)
        from(jpackageDir.map { it.dir("output") })
        archiveFileName.set("capebabe-${project.version}-$platform.zip")
        destinationDirectory.set(distDir)
    }
    packageApp.configure { dependsOn(zipApp) }
} else {
    val tarApp = tasks.register<Tar>("tarApp") {
        dependsOn(jpackage)
        from(jpackageDir.map { it.dir("output") })
        compression = Compression.GZIP
        archiveFileName.set("capebabe-${project.version}-$platform.tar.gz")
        destinationDirectory.set(distDir)
    }
    packageApp.configure { dependsOn(tarApp) }
}
