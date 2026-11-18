// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.cyclonedx.bom") version "3.6.1"
}

// Release signing material lives in the git-ignored keystore/ folder.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.fastkiwi.scan"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    // 2.0-ocr: the NDK GitHub's ubuntu-24.04 runner ships as its default (CI builds the MNN JNI glue).
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.fastkiwi.scan"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "2.0.2"
        // Arabic is the default (values/), English in values-en/.
        resourceConfigurations += listOf("ar", "en")
        ndk {
            // 2.0-ocr: real phones only (arm64 / armv7). x86_64 was dropped in the 1.5 plan: MNN has no
            // official x86_64 Android build and it would add ~3.4 MB.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // 2.0-ocr: JNI glue for MNN (Accurate OCR). libc++_shared because the official libMNN.so needs it.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 2.0-ocr: builds app/src/main/cpp (kiwi_mnn) against the pinned libMNN.so.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed native libs keep the APK small enough to send in chat.
        jniLibs {
            useLegacyPackaging = true
            // 2.0-ocr: ship MNN's official libMNN.so byte for byte (pinned sha256 in THIRD_PARTY.md / SBOM).
            keepDebugSymbols += "**/libMNN.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    // 2.0-pdf: PdfBox-Android ships 4.8 MB of assets. Left out: the CJK CMaps (only Identity-H/V
    // stay) and Unicode Scripts.txt (font shaping, unused). Kept: AFM metrics of the 14 standard
    // fonts, the Adobe glyph lists, BidiMirroring.txt (text extraction needs them) and the
    // LiberationSans last-resort font (OFL-1.1), which PdfBox's font mapper always loads.
    androidResources {
        ignoreAssetsPatterns += listOf(
            "!*-RKSJ-*", "!Adobe-CNS1-*", "!Adobe-GB1-*", "!Adobe-Japan*", "!Adobe-Korea1-*", "!B5pc-*",
            "!CNS-EUC-*", "!ETen*", "!EUC-H", "!EUC-V", "!GB*", "!HKscs-*", "!KSC*", "!Uni*", "!H", "!V",
            "!Scripts.txt",
        )
    }

    // No dependency-metadata blob in the APK (privacy, size).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "3g"
            // 2.0-perf: the heap tests read native memory as process memory minus the JVM heap; a heap
            // that never shrinks after GC keeps G1's delayed uncommit out of that number.
            it.jvmArgs("-XX:MaxHeapFreeRatio=100")
            // 2.0.1 (Linux CI): there the meter reads RssAnon, which counts only touched pages. Pre-touch
            // the heap so committed heap is resident heap (else Java allocations into fresh heap pages
            // read as native growth), and pin glibc's mmap threshold so large native blocks (page
            // bitmaps) are mapped and unmapped on every allocation, as on Windows and on Android, instead
            // of being kept in a malloc arena that the next bitmap reuses unseen (glibc raises the
            // threshold to 32 MB after the first large free). Ignored on Windows. MemoryMeter checks both.
            it.jvmArgs("-XX:+AlwaysPreTouch")
            it.environment("GLIBC_TUNABLES", "glibc.malloc.mmap_threshold=131072:glibc.malloc.trim_threshold=131072")
            // 2.0-perf: the 500-page stress test runs only in perfStressTest (512 MB heap cap).
            it.filter.excludeTestsMatching("com.fastkiwi.scan.perf.StressHeapCapTest")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// 2.0-perf (ROADMAP 1.7.0): "no OutOfMemoryError in a 500-page synthetic stress test on the JVM with a
// 512 MB heap cap". The stress class runs in its own test JVM capped at 512 MB, after every debug
// unit-test run; the main unit-test JVM (3 GB) excludes it.
val perfStressTest = tasks.register<Test>("perfStressTest") {
    description = "500-page export stress test with a 512 MB heap cap (2.0-perf)."
    group = "verification"
    val unit = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unit.testClassesDirs
    classpath = unit.classpath
    systemProperties(unit.systemProperties)
    jvmArgs(unit.jvmArgs ?: emptyList<String>())
    jvmArgumentProviders.addAll(unit.jvmArgumentProviders)
    environment(unit.environment)
    workingDir = unit.workingDir
    maxHeapSize = "512m"
    filter.includeTestsMatching("com.fastkiwi.scan.perf.StressHeapCapTest")
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/perfStressTest"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/perfStressTest"))
}
// A run filtered with --tests (one class while working) skips it; run :app:perfStressTest directly then.
val filteredRun = gradle.startParameter.taskRequests.any { r -> r.args.any { it.startsWith("--tests") } }
perfStressTest.configure { onlyIf { !filteredRun || gradle.startParameter.taskNames.any { it.endsWith("perfStressTest") } } }
afterEvaluate {
    tasks.named("testDebugUnitTest") { finalizedBy(perfStressTest) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val camerax = "1.5.3"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // On-device OCR (Apache-2.0), Arabic + English models bundled in assets.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // 2.0-pdf: PdfBox-Android (Apache-2.0) for lossless merge/split/rotate/text of external PDFs.
    // Runs only inside the isolated ":pdf" service process. BouncyCastle (public-key
    // encryption only) is left out; password-protected files use javax.crypto.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") { exclude(group = "org.bouncycastle") }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // Used only by unit tests to independently parse/decrypt the PDFs we write.
    testImplementation("org.apache.pdfbox:pdfbox:3.0.6")
    // JVM UI smoke tests + screenshots (Robolectric native graphics); not shipped.
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Independent PDF/A validator (veraPDF, MPL-2.0 / GPL-3.0+), used only to check our PDF/A-2b output.
    testImplementation("org.verapdf:validation-model:1.30.4")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------
// Release SBOM (CycloneDX). The plugin lists the Maven dependencies shipped in
// the APK; `releaseSbom` adds what Maven cannot see: the native libraries inside
// tesseract4android, the bundled OCR models, the glyphless font, the ICC profile,
// the Noto Sans Arabic UI font and the Material Symbols icons.
// ---------------------------------------------------------------------------
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    componentGroup.set("com.fastkiwi")
    componentName.set("scan-kiwi")
    componentVersion.set(android.defaultConfig.versionName)
    jsonOutput.set(layout.buildDirectory.file("reports/sbom/scan-kiwi.cdx.json"))
}

tasks.register("releaseSbom") {
    description = "Writes dist/ScanKiwi-<version>.cdx.json (CycloneDX SBOM of the release APK)."
    dependsOn("cyclonedxDirectBom")
    val input = layout.buildDirectory.file("reports/sbom/scan-kiwi.cdx.json")
    val version = android.defaultConfig.versionName
    val assets = file("src/main/assets")
    val output = rootProject.file("dist/ScanKiwi-$version.cdx.json")
    inputs.file(input)
    outputs.file(output)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val bom = groovy.json.JsonSlurper().parse(input.get().asFile) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val components = (bom["components"] as? MutableList<Any?>) ?: mutableListOf<Any?>().also { bom["components"] = it }
        fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        fun lib(name: String, version: String, license: String, url: String) = mapOf(
            "type" to "library", "bom-ref" to "native:$name", "name" to name, "version" to version,
            "description" to "Native library bundled inside cz.adaptech.tesseract4android:tesseract4android:4.9.0",
            "licenses" to listOf(mapOf("license" to mapOf("id" to license))),
            "externalReferences" to listOf(mapOf("type" to "website", "url" to url)),
        )
        components += lib("tesseract", "5 (as built by Tesseract4Android 4.9.0)", "Apache-2.0", "https://github.com/tesseract-ocr/tesseract")
        components += lib("leptonica", "as built by Tesseract4Android 4.9.0", "BSD-2-Clause", "http://www.leptonica.org/")
        components += lib("libjpeg-turbo", "as built by Tesseract4Android 4.9.0", "IJG", "https://libjpeg-turbo.org/")
        components += lib("libpng", "as built by Tesseract4Android 4.9.0", "Libpng", "http://www.libpng.org/pub/png/libpng.html")
        fun data(path: String, name: String, license: String, url: String) = mapOf(
            "type" to "data", "bom-ref" to "asset:$path", "name" to name,
            "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to sha256(File(assets, path)))),
            "licenses" to listOf(mapOf("license" to mapOf("id" to license))),
            "externalReferences" to listOf(mapOf("type" to "distribution", "url" to url)),
        )
        components += data("tessdata/ara.traineddata", "tessdata_fast ara.traineddata", "Apache-2.0", "https://github.com/tesseract-ocr/tessdata_fast")
        components += data("tessdata/eng.traineddata", "tessdata_fast eng.traineddata", "Apache-2.0", "https://github.com/tesseract-ocr/tessdata_fast")
        // 2.0-ocr: Accurate-mode models and the MNN runtime (not Maven artifacts).
        components += data("ocr/PP-OCRv6_det_tiny.fp16.mnn", "PP-OCRv6 tiny text detector (PaddlePaddle), MNN fp16", "Apache-2.0", "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det")
        components += data("ocr/arabic_PP-OCRv5_rec_mobile.fp16.mnn", "PP-OCRv5 Arabic mobile recogniser (PaddlePaddle), MNN fp16", "Apache-2.0", "https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec")
        components += data("ocr/ppocrv5_arabic_dict.txt", "PP-OCRv5 Arabic recogniser dictionary", "Apache-2.0", "https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec")
        val jni = file("src/main/jniLibs")
        for (abi in listOf("arm64-v8a", "armeabi-v7a")) {
            components += mapOf(
                "type" to "library", "bom-ref" to "native:MNN:$abi", "name" to "MNN libMNN.so ($abi)", "version" to "3.6.2",
                "description" to "Official MNN Android release, CPU runtime (tools/mnn/README.md)",
                "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to sha256(File(jni, "$abi/libMNN.so")))),
                "licenses" to listOf(mapOf("license" to mapOf("id" to "Apache-2.0"))),
                "externalReferences" to listOf(mapOf("type" to "distribution", "url" to "https://github.com/alibaba/MNN/releases/tag/3.6.2")),
            )
        }
        components += mapOf(
            "type" to "library", "bom-ref" to "native:libc++_shared", "name" to "LLVM libc++ (libc++_shared.so)", "version" to "Android NDK 27.3.13750724",
            "licenses" to listOf(mapOf("expression" to "Apache-2.0 WITH LLVM-exception")),
            "externalReferences" to listOf(mapOf("type" to "website", "url" to "https://developer.android.com/ndk")),
        )
        components += data("fonts/glyphless.ttf", "Tesseract pdf.ttf (GlyphLessFont)", "Apache-2.0", "https://github.com/tesseract-ocr/tesseract/tree/main/tessdata")
        components += data("icc/sRGB-v2-micro.icc", "sRGB-v2-micro.icc", "CC0-1.0", "https://github.com/saucecontrol/Compact-ICC-Profiles")
        // Bundled UI font (res/font) and icons (res/drawable), which Maven does not list either.
        val fonts = file("src/main/res/font")
        for (w in listOf("regular", "medium", "bold")) {
            val f = File(fonts, "noto_sans_arabic_$w.ttf")
            components += mapOf(
                "type" to "data", "bom-ref" to "font:noto_sans_arabic_$w.ttf", "name" to "Noto Sans Arabic ${w.replaceFirstChar { it.uppercase() }}",
                "version" to "2.013",
                "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to sha256(f))),
                "licenses" to listOf(mapOf("license" to mapOf("id" to "OFL-1.1"))),
                "externalReferences" to listOf(mapOf("type" to "distribution", "url" to "https://github.com/notofonts/arabic/releases")),
            )
        }
        components += mapOf(
            "type" to "data", "bom-ref" to "asset:res/drawable/ic_sym_*.xml", "name" to "Material Symbols Rounded (vector drawables)",
            "licenses" to listOf(mapOf("license" to mapOf("id" to "Apache-2.0"))),
            "externalReferences" to listOf(mapOf("type" to "distribution", "url" to "https://github.com/google/material-design-icons")),
        )
        // 2.0: data files bundled inside the PdfBox-Android AAR (its POM only says Apache-2.0).
        // APAFML accepted by the lead (2.0, permissive, SPDX-listed; Fedora and Debian accept it).
        for ((name, lic) in listOf(
            "Adobe Core 14 AFM font metrics (inside pdfbox-android)" to "APAFML",
            "Adobe Glyph List and ITC Zapf Dingbats glyph list (inside pdfbox-android)" to "BSD-3-Clause",
            "Unicode BidiMirroring.txt (inside pdfbox-android)" to "Unicode-DFS-2015",
            "Liberation Sans Regular 2.1.5 (inside pdfbox-android)" to "OFL-1.1",
        )) {
            components += mapOf(
                "type" to "data", "bom-ref" to "pdfbox-android-data:" + name.substringBefore(" ("), "name" to name,
                "licenses" to listOf(mapOf("license" to mapOf("id" to lic))),
                "externalReferences" to listOf(mapOf("type" to "distribution", "url" to "https://github.com/TomRoush/PdfBox-Android")),
            )
        }
        // Fix licence metadata the POMs get wrong or leave out.
        @Suppress("UNCHECKED_CAST")
        for (c in components.filterIsInstance<MutableMap<String, Any?>>()) {
            if (c["group"] == "cz.adaptech.tesseract4android" && c["licenses"] == null) {
                c["licenses"] = listOf(mapOf("license" to mapOf("id" to "Apache-2.0")))
            }
            // camera-core's POM names libyuv's licence "BSD License", which the plugin maps to
            // BSD-4-Clause; libyuv is BSD-3-Clause.
            if (c["group"] == "androidx.camera" && c["name"] == "camera-core") {
                c["licenses"] = listOf(mapOf("license" to mapOf("id" to "Apache-2.0")), mapOf("license" to mapOf("id" to "BSD-3-Clause")))
            }
        }
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(bom)) + "\n")
    }
}

// ---------------------------------------------------------------------------
// 2.0.1 release guard. Scan Kiwi 2.0.0 shipped a release build whose export failed on a phone while
// every debug test passed. `releaseGuardTest` runs app/src/releaseGuard against the RELEASE program:
// the same R8 (bundled with AGP), the same inputs and the same merged rules as minifyReleaseWithR8
// (its printed configuration.txt), written as class files instead of dex, plus the release manifest,
// the shrunk and optimized release resources and the release assets, under Robolectric.
// `releaseKeepCheck` (cheap, no Robolectric) reads the R8 outputs of the release build.
// ---------------------------------------------------------------------------
val releaseGuardRuntime: Configuration by configurations.creating
dependencies {
    releaseGuardRuntime("junit:junit:4.13.2")
    releaseGuardRuntime("org.robolectric:robolectric:4.16.1")
    // An independent PDF reader for the files the release build writes (not shipped).
    releaseGuardRuntime("org.apache.pdfbox:pdfbox:3.0.6")
}

// Robolectric pulls AARs (androidx.test); the test classpath takes their classes jars.
val releaseGuardJars = releaseGuardRuntime.incoming.artifactView {
    attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
}.files

val releaseGuardShrink = tasks.register("releaseGuardShrink") {
    description = "Shrinks the release program with R8 to class files (same inputs and rules as minifyReleaseWithR8)."
    dependsOn("minifyReleaseWithR8")
    val outJar = layout.buildDirectory.file("releaseGuard/release-r8.jar")
    val work = layout.buildDirectory.dir("releaseGuard/in")
    val cfg = layout.buildDirectory.file("outputs/mapping/release/configuration.txt")
    // Re-run whenever the release program changes (minifyReleaseWithR8's outputs stand for its inputs).
    inputs.files(tasks.named("minifyReleaseWithR8").map { it.outputs.files })
    outputs.file(outJar)
    doLast {
        val r8Task = tasks.getByName("minifyReleaseWithR8") as com.android.build.gradle.internal.tasks.ProguardConfigurableTask
        val r8 = tasks.getByName("minifyReleaseWithR8") as com.android.build.gradle.internal.tasks.R8Task
        val workDir = work.get().asFile.apply { deleteRecursively(); mkdirs() }
        // R8 takes jars: class directories are zipped first.
        val program = r8Task.classes.files.filter { it.exists() }.mapIndexed { i, f ->
            if (f.isFile) f.toPath() else File(workDir, "dir$i.jar").also { jar ->
                ant.withGroovyBuilder { "zip"("destfile" to jar, "basedir" to f) }
            }.toPath()
        }
        val out = outJar.get().asFile.apply { parentFile.mkdirs(); delete() }
        val builder = com.android.tools.r8.R8Command.builder()
            .addProgramFiles(program)
            .addLibraryFiles(r8.bootClasspath.files.map { it.toPath() })
            .addLibraryFiles(r8Task.referencedClasses.files.filter { it.isFile }.map { it.toPath() })
            .addProguardConfigurationFiles(cfg.get().asFile.toPath())
            .setMode(com.android.tools.r8.CompilationMode.RELEASE)
            .setProguardMapOutputPath(File(out.parentFile, "mapping.txt").toPath())
            .setProgramConsumer(com.android.tools.r8.ClassFileConsumer.ArchiveConsumer(out.toPath(), true))
        r8Task.resourcesJar.orNull?.asFile?.takeIf { it.exists() }?.let {
            builder.addProgramResourceProvider(com.android.tools.r8.ArchiveProgramResourceProvider.fromArchive(it.toPath()))
        }
        com.android.tools.r8.R8.run(builder.build())
    }
}

val releaseGuardCompile = tasks.register<JavaCompile>("releaseGuardCompile") {
    dependsOn(releaseGuardShrink)
    source = fileTree("src/releaseGuard/java")
    val boot = files(android.bootClasspath)
    classpath = releaseGuardJars + boot
    destinationDirectory.set(layout.buildDirectory.dir("releaseGuard/test-classes"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

// The release resources with the release assets inside, as in the APK (Robolectric reads assets from it):
// files only, aligned like an APK (Android 8's package parser is strict about both).
val releaseGuardResources = tasks.register("releaseGuardResources") {
    dependsOn("optimizeReleaseResources", "mergeReleaseAssets")
    val res = layout.buildDirectory.file("intermediates/optimized_processed_res/release/optimizeReleaseResources/resources-release-optimize.ap_")
    val assets = layout.buildDirectory.dir("intermediates/assets/release/mergeReleaseAssets")
    val unaligned = layout.buildDirectory.file("releaseGuard/release-resources-unaligned.ap_")
    val aligned = layout.buildDirectory.file("releaseGuard/release-resources.ap_")
    val zipalign = File(android.sdkDirectory, "build-tools/${android.buildToolsVersion}/" + if (System.getProperty("os.name").startsWith("Windows")) "zipalign.exe" else "zipalign")
    inputs.files(res, assets)
    outputs.file(aligned)
    doLast {
        val u = unaligned.get().asFile.apply { delete() }
        ant.withGroovyBuilder {
            "zip"("destfile" to u, "filesonly" to true, "keepcompression" to true) {
                "zipfileset"("src" to res.get().asFile)
                "zipfileset"("dir" to assets.get().asFile, "prefix" to "assets")
            }
        }
        providers.exec { commandLine(zipalign.absolutePath, "-f", "-p", "4", u.absolutePath, aligned.get().asFile.absolutePath) }.result.get().assertNormalExitValue()
    }
}

val releaseGuardConfig = tasks.register("releaseGuardConfig") {
    dependsOn(releaseGuardResources, "processReleaseManifestForPackage")
    val dir = layout.buildDirectory.dir("releaseGuard/config")
    val build = layout.buildDirectory
    outputs.dir(dir)
    outputs.upToDateWhen { false } // a three-line file
    doLast {
        val b = build.get().asFile
        val f = File(dir.get().asFile, "com/android/tools/test_config.properties").apply { parentFile.mkdirs() }
        fun p(rel: String) = File(b, rel).absolutePath.replace("\\", "/")
        f.writeText(
            "android_merged_manifest=" + p("intermediates/packaged_manifests/release/processReleaseManifestForPackage/AndroidManifest.xml") + "\n" +
                "android_merged_assets=" + p("intermediates/assets/release/mergeReleaseAssets") + "\n" +
                "android_resource_apk=" + p("releaseGuard/release-resources.ap_") + "\n" +
                "android_custom_package=com.fastkiwi.scan\n",
        )
    }
}

fun Test.releaseGuardCommon() {
    group = "verification"
    dependsOn(releaseGuardCompile, releaseGuardConfig)
    testClassesDirs = files(layout.buildDirectory.dir("releaseGuard/test-classes"))
    classpath = files(
        layout.buildDirectory.dir("releaseGuard/test-classes"),
        layout.buildDirectory.dir("releaseGuard/config"),
        layout.buildDirectory.file("releaseGuard/release-r8.jar"),
    ) + releaseGuardJars + files(android.bootClasspath)
    maxHeapSize = "3g"
    // As the unit-test JVM: Robolectric moves FileDescriptor fields (BitmapFactory.decodeFile).
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED")
    systemProperty("kiwi.guard.out", layout.buildDirectory.dir("releaseGuard/out").get().asFile.absolutePath)
    // -PguardLog: the app's log and the test's output in the console.
    if (providers.gradleProperty("guardLog").isPresent) systemProperty("robolectric.logging", "stdout")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = providers.gradleProperty("guardLog").isPresent }
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$name"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$name"))
}

val releaseGuardTest = tasks.register<Test>("releaseGuardTest") {
    description = "Export/Share/Save on the R8-shrunk release program, Android 8 to 16 (2.0.1 guard)."
    releaseGuardCommon()
    filter.includeTestsMatching("com.fastkiwi.scan.guard.ReleaseExportGuardTest")
    // CI runs a subset of the Android versions (-PguardSdks=26,29,33,36); locally all of them.
    providers.gradleProperty("guardSdks").orNull?.let { systemProperty("robolectric.enabledSdks", it) }
}

// A test server certificate for the fake GitHub (made with the JDK's keytool; never shipped), and a
// trust store of the JDK's CAs plus it.
val releaseGuardCerts = tasks.register("releaseGuardCerts") {
    val dir = layout.buildDirectory.dir("releaseGuard/certs")
    outputs.dir(dir)
    doLast {
        val d = dir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val home = System.getProperty("java.home")
        val keytool = File(home, "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool").absolutePath
        fun kt(vararg a: String) = providers.exec { commandLine(keytool, *a) }.result.get().assertNormalExitValue()
        val server = File(d, "server.p12").absolutePath
        val cer = File(d, "server.cer").absolutePath
        val trust = File(d, "trust.p12").absolutePath
        kt("-genkeypair", "-alias", "fake", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=api.github.com",
            "-ext", "SAN=dns:api.github.com,dns:github.com,dns:objects.githubusercontent.com,dns:release-assets.githubusercontent.com",
            "-keystore", server, "-storetype", "PKCS12", "-storepass", "guard-only", "-keypass", "guard-only")
        kt("-exportcert", "-alias", "fake", "-keystore", server, "-storepass", "guard-only", "-file", cer)
        kt("-importkeystore", "-srckeystore", File(home, "lib/security/cacerts").absolutePath, "-srcstorepass", "changeit",
            "-destkeystore", trust, "-deststoretype", "PKCS12", "-deststorepass", "guard-only", "-noprompt")
        kt("-importcert", "-noprompt", "-alias", "fake-github", "-file", cer, "-keystore", trust, "-storepass", "guard-only")
        File(d, "hosts").writeText("127.0.0.1 api.github.com github.com objects.githubusercontent.com release-assets.githubusercontent.com\n")
    }
}

tasks.register<Test>("releaseUpdateGuardTest") {
    description = "In-app update check and download against a local fake GitHub, on the R8-shrunk release program (2.0.1 guard)."
    releaseGuardCommon()
    dependsOn(releaseGuardCerts)
    // Android's SDK jars are fetched by the export guard; this JVM resolves only the fake hosts.
    mustRunAfter(releaseGuardTest)
    filter.includeTestsMatching("com.fastkiwi.scan.guard.UpdateGuardTest")
    val certs = layout.buildDirectory.dir("releaseGuard/certs").get().asFile
    systemProperty("jdk.net.hosts.file", File(certs, "hosts").absolutePath)
    systemProperty("javax.net.ssl.trustStore", File(certs, "trust.p12").absolutePath)
    systemProperty("javax.net.ssl.trustStorePassword", "guard-only")
    systemProperty("kiwi.guard.keystore", File(certs, "server.p12").absolutePath)
    // Any archive stands in for the APK: the checksum is what the guard checks.
    systemProperty("kiwi.guard.apk", layout.buildDirectory.file("releaseGuard/release-r8.jar").get().asFile.absolutePath)
}

// Cheap CI check on the real release build (no device, no Robolectric): what the app reaches by name
// (manifest components, JNI, PdfBox data, FileProvider paths, export fonts) is still in the APK.
tasks.register("releaseKeepCheck") {
    description = "Checks that R8 and resource shrinking kept what the release app needs by name (2.0.1)."
    group = "verification"
    dependsOn("assembleRelease")
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    doLast {
        val problems = ArrayList<String>()
        // Classes that must keep their names: manifest components, JNI, the share sheet's FileProvider.
        val named = listOf(
            "com.fastkiwi.scan.MainActivity", "com.fastkiwi.scan.pdf.PdfService", "com.fastkiwi.scan.update.UpdateInstallReceiver",
            "androidx.core.content.FileProvider", "com.fastkiwi.scan.ocr.fused.MnnNative",
            "com.googlecode.tesseract.android.TessBaseAPI",
        )
        val kept = HashSet<String>()
        mapping.get().asFile.forEachLine { line ->
            if (!line.startsWith(" ") && !line.startsWith("#")) {
                val m = Regex("""^(\S+) -> (\S+):$""").find(line)
                if (m != null && m.groupValues[1] == m.groupValues[2]) kept += m.groupValues[1]
            }
        }
        named.filter { it !in kept }.forEach { problems += "class renamed or removed: $it" }
        val apk = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }?.firstOrNull() ?: throw GradleException("no release APK")
        val entries = ZipFile(apk).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        val needed = listOf(
            "assets/fonts/glyphless.ttf", "assets/icc/sRGB-v2-micro.icc",
            "assets/tessdata/ara.traineddata", "assets/tessdata/eng.traineddata",
            "assets/com/tom_roush/pdfbox/resources/glyphlist/glyphlist.txt",
            "assets/com/tom_roush/pdfbox/resources/afm/Helvetica.afm",
            "assets/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf",
        )
        needed.filter { it !in entries }.forEach { problems += "missing from the APK: $it" }
        // The FileProvider paths (res/xml/file_paths) must survive resource shrinking and still name the exports folder.
        val aapt2 = File(android.sdkDirectory, "build-tools/${android.buildToolsVersion}/" + if (System.getProperty("os.name").startsWith("Windows")) "aapt2.exe" else "aapt2")
        val dump = providers.exec { commandLine(aapt2.absolutePath, "dump", "resources", apk.absolutePath) }.standardOutput.asText.get()
        val pathsFile = Regex("""resource 0x[0-9a-f]+ xml/file_paths\s+\(\) \(file\) (\S+)""").find(dump)?.groupValues?.get(1)
        if (pathsFile == null) problems += "xml/file_paths was removed by resource shrinking"
        else {
            val tree = providers.exec { commandLine(aapt2.absolutePath, "dump", "xmltree", apk.absolutePath, "--file", pathsFile) }.standardOutput.asText.get()
            if (!tree.contains("cache-path") || !tree.contains("\"exports/\"")) problems += "file_paths no longer shares cache/exports/"
        }
        for (font in listOf("noto_sans_arabic_regular", "noto_sans_arabic_medium", "noto_sans_arabic_bold")) {
            if (!dump.contains("font/$font")) problems += "font/$font was removed by resource shrinking"
        }
        if (problems.isNotEmpty()) throw GradleException("Release keep check failed:\n  " + problems.joinToString("\n  "))
        println("releaseKeepCheck: ${named.size} classes, ${needed.size} assets, file_paths and fonts present in ${apk.name}")
    }
}
