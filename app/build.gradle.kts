import com.android.build.api.variant.AndroidComponentsExtension
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Packs magisk/hwkeyboard_langswitch into a flashable zip and hands it to the APK as an
 * asset, so the app can install the Magisk module itself.
 *
 * The zip is built here rather than by a shell script on purpose: nothing about setting
 * this module up may require a terminal, and that includes producing the artefact. The
 * module ships no key layouts - customize.sh derives them from whatever ROM it lands on -
 * so this is a handful of text files and the zip is cheap to rebuild every time.
 */
abstract class PackMagiskModule : DefaultTask() {

    @get:InputDirectory
    abstract val moduleDir: DirectoryProperty

    @get:Input
    abstract val zipName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val src = moduleDir.get().asFile
        val zip = outputDir.get().asFile.resolve(zipName.get())
        zip.parentFile.mkdirs()
        zip.delete()

        // Deterministic order and fixed timestamps, so an unchanged module produces an
        // unchanged zip and the APK does not churn.
        val files = src.walkTopDown().filter { it.isFile }.sortedBy {
            it.relativeTo(src).invariantSeparatorsPath
        }
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for (f in files) {
                val name = f.relativeTo(src).invariantSeparatorsPath
                val entry = ZipEntry(name)
                entry.time = 0L
                out.putNextEntry(entry)
                // The .sh files must reach the device with LF endings: Magisk's shell
                // would read a CRLF customize.sh as commands with a trailing CR.
                if (name.endsWith(".sh") || name.endsWith("update-binary") ||
                    name.endsWith("updater-script") || name.endsWith(".prop")
                ) {
                    out.write(f.readText().replace("\r\n", "\n").toByteArray())
                } else {
                    out.write(f.readBytes())
                }
                out.closeEntry()
            }
        }
        logger.lifecycle("packed ${files.count()} files into $zip")
    }
}

val packMagiskModule = tasks.register<PackMagiskModule>("packMagiskModule") {
    moduleDir.set(rootProject.layout.projectDirectory.dir("magisk/hwkeyboard_langswitch"))
    zipName.set("hwkeyboard_langswitch.zip")
    outputDir.set(layout.buildDirectory.dir("generated/magiskModule"))
}

// The external-display tablet fix: a Zygisk module (native .so snapshotted into magisk/), packed
// so the app can install it itself, exactly like the keyboard module above.
val packExtDensityModule = tasks.register<PackMagiskModule>("packExtDensityModule") {
    moduleDir.set(rootProject.layout.projectDirectory.dir("magisk/zygisk_extdensity"))
    zipName.set("zygisk_extdensity.zip")
    outputDir.set(layout.buildDirectory.dir("generated/extDensityModule"))
}

extensions.configure<AndroidComponentsExtension<*, *, *>>("androidComponents") {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            packMagiskModule, PackMagiskModule::outputDir,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            packExtDensityModule, PackMagiskModule::outputDir,
        )
    }
}

/**
 * Release signing.
 *
 * The keystore is deliberately NOT in this repository - not even gitignored inside it, because a
 * published repo is one `git add -f` away from leaking a signing key that can never be rotated.
 * `keystore.properties` (gitignored) sits at the repo root and points at a keystore kept elsewhere.
 *
 * A clone without that file still builds: the release variant simply comes out unsigned, the way
 * `assembleRelease` behaved before. It fails loudly only if the file exists but names a keystore
 * that does not - that means a machine is misconfigured, and silently shipping an unsigned APK
 * would be the worse outcome.
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.laelaps.zuitweaks"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.laelaps.zuitweaks"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        keystoreProperties.getProperty("storeFile")?.let { path ->
            val ks = file(path)
            require(ks.exists()) {
                "keystore.properties names a keystore that does not exist: $path"
            }
            create("release") {
                storeFile = ks
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                // AGP leaves v3 off by default. It is worth having on a key meant to be permanent:
                // v3 is what carries a proof-of-rotation lineage, so it is the prerequisite for ever
                // replacing this key without every install becoming an uninstall-first.
                enableV2Signing = true
                enableV3Signing = true
                // v1 (JAR) is for Android < 7.0 and minSdk here is 28. v4 only serves
                // `adb install --incremental` and emits a separate .apk.idsig sidecar.
                enableV1Signing = false
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            // null when there is no keystore.properties - AGP then leaves the APK unsigned.
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Xposed API: compileOnly is mandatory - bundling it breaks module loading
    compileOnly(libs.xposed.api)
    // libXposed service: the app-side channel for remote preferences (replaces the legacy
    // world-readable SharedPreferences + xposedsharedprefs bridge). Bundled (implementation)
    // so this app can bind XposedService and write prefs the module reads via
    // getRemotePreferences. Also contributes the XposedProvider that receives the binder.
    implementation(libs.libxposed.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}