// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
import java.security.MessageDigest
import java.util.Properties
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

    defaultConfig {
        applicationId = "com.fastkiwi.scan"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"
        // Arabic is the default (values/), English in values-en/.
        resourceConfigurations += listOf("ar", "en")
        ndk {
            // Real phones (arm64 / armv7) plus x86_64 for emulators and Chromebooks.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed native libs keep the APK small enough to send in chat.
        jniLibs { useLegacyPackaging = true }
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

    // No dependency-metadata blob in the APK (privacy, size).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "3g" }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
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
