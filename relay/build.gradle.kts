plugins {
    application
}

dependencies {
    implementation(project(":common"))
}

application {
    mainClass = "com.yamikhal.e4hytale.relay.Relay"
}

// one self-contained jar for the Docker image (the relay has no dependencies besides :common)
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.yamikhal.e4hytale.relay.Relay",
            "Implementation-Title" to "e4hytale-relay",
            "Implementation-Version" to project.version,
        )
    }
    dependsOn(":common:jar")
    from(project(":common").sourceSets.main.map { it.output })
}

// runs the relay locally on the default ports, for testing against a local Hytale server
tasks.named<JavaExec>("run") {
    workingDir = layout.projectDirectory.dir("run").asFile.apply { mkdirs() }
    environment("E4H_DOMAIN", providers.gradleProperty("e4h_domain").getOrElse("e4h.yamikhal.com"))
}
