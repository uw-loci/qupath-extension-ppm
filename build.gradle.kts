plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
    // Code formatting (like Black for Python)
    id("com.diffplug.spotless") version "7.0.2"
    // Static bug detection
    id("com.github.spotbugs") version "6.1.2"
}

// Configure your extension here
qupathExtension {
    name = "qupath-extension-ppm"
    group = "io.github.uw-loci"
    version = "0.1.3"
    description = "Polarized light microscopy (PPM) modality extension for QuPath/QPSC"
    automaticModule = "io.github.uw.loci.extension.ppm"
}

allprojects {
    repositories {
        mavenLocal() // Checks your local Maven repository first (for QPSC dependency)
        mavenCentral()
        maven {
            name = "SciJava"
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
        maven {
            name = "OME-Artifacts"
            url = uri("https://artifacts.openmicroscopy.org/artifactory/maven/")
        }
        maven {
            name = "OME"
            url = uri("https://repo.openmicroscopy.org/artifactory/ome-releases")
        }
    }
}

dependencies {
    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.snakeyaml)
    shadow(libs.gson)
    shadow(libs.bundles.groovy)

    // Depend on QPSC for ModalityHandler, ModalityRegistry, socket client, config manager
    shadow("io.github.uw-loci:qupath-extension-qpsc:0.4.1")

    // Appose -- embedded Python environment for PPM analysis (ppm_library)
    implementation("org.apposed:appose:0.10.0")

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("io.github.qupath:qupath-app:0.6.0-rc4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}

// Merge META-INF/services files so ServiceLoader discovers Appose implementations
tasks.shadowJar {
    mergeServiceFiles()
}

// For troubleshooting deprecation warnings
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
    doFirst {
        val cp = classpath.files
        val fxJars = cp.filter { it.name.startsWith("javafx-") }
        if (fxJars.isNotEmpty()) {
            classpath = files(cp - fxJars)
            jvmArgs(
                "--module-path", fxJars.joinToString(File.pathSeparator),
                "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
                "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Spotless -- auto-formatting
// ---------------------------------------------------------------------------
spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.50.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---------------------------------------------------------------------------
// ASCII-only enforcement (CLAUDE.md policy: no chars > 0x7F in Java sources).
// Prevents the recurring Windows cp1252 encoding failures.
// ---------------------------------------------------------------------------
tasks.register("checkAsciiOnly") {
    description = "Fails if any Java source file contains non-ASCII characters (> 0x7F)"
    group = "verification"
    val srcDirs = fileTree("src") { include("**/*.java") }
    inputs.files(srcDirs)
    doLast {
        val violations = mutableListOf<String>()
        srcDirs.forEach { file ->
            file.readText().lines().forEachIndexed { idx, line ->
                line.forEachIndexed { col, ch ->
                    if (ch.code > 0x7F) {
                        violations.add(
                            "${file.relativeTo(projectDir)}:${idx + 1}:${col + 1}  " +
                            "'$ch' (U+${"04X".format(ch.code)})"
                        )
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Non-ASCII characters found (will break on Windows cp1252):\n" +
                violations.joinToString("\n")
            )
        }
        logger.lifecycle("checkAsciiOnly: all Java sources are ASCII-clean")
    }
}
tasks.named("check") { dependsOn("checkAsciiOnly") }

// ---------------------------------------------------------------------------
// SpotBugs -- static bug detection
// ---------------------------------------------------------------------------
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}
