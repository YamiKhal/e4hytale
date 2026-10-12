plugins {
    java
}

val javaVersion = 25

subprojects {
    apply(plugin = "java")

    group = "com.yamikhal.e4hytale"
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(javaVersion)
        }
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        "compileOnly"(libs.findLibrary("jsr305").get())
        "testCompileOnly"(libs.findLibrary("jsr305").get())
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = javaVersion
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
