// ---------------------------------------------------------------------------
// Undal HomeKitchen — :app build script
//
// Everything injectable comes from CI secrets (via -P flags) or from
// gradle.properties defaults, then lands in BuildConfig. Nothing sensitive is
// ever written into source.
// ---------------------------------------------------------------------------

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
}

/**
 * Reads a build input from -P<name>, then the environment (CI secrets), then the
 * gradle.properties default. Blank values are treated as "not provided" so an
 * unset repository secret can never blank out a BuildConfig constant.
 */
fun buildInput(name: String, fallback: String = ""): String {
    val fromProperty = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
    if (fromProperty != null) return fromProperty
    val fromEnvironment = System.getenv(name)?.takeIf { it.isNotBlank() }
    if (fromEnvironment != null) return fromEnvironment
    return fallback
}

/** Escapes a value for a generated BuildConfig String constant. */
fun asBuildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val undalVersionCode = (buildInput("undalVersionCode", "1")).toIntOrNull() ?: 1
val undalVersionName = buildInput("undalVersionName", "1.0.$undalVersionCode")

val cloudinaryCloudName = buildInput("UNDAL_CLOUDINARY_CLOUD_NAME", "undal")
val cloudinaryUploadPreset = buildInput("UNDAL_CLOUDINARY_UPLOAD_PRESET", "undal_unsigned")
val workerBaseUrl = buildInput("UNDAL_WORKER_BASE_URL", "https://undal.YOUR-SUBDOMAIN.workers.dev")
val workerHost = buildInput("UNDAL_WORKER_HOST", "undal.YOUR-SUBDOMAIN.workers.dev")
val firebaseWebApiKey = buildInput("UNDAL_FIREBASE_WEB_API_KEY")
val supportPhone = buildInput("UNDAL_SUPPORT_PHONE")
val undalMinify = buildInput("undalMinify", "false").toBoolean()

android {
    namespace = "com.undal.syl.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = libs.versions.packageName.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = undalVersionCode
        versionName = undalVersionName

        // Deep links (PART N): the Cloudflare Worker host is written at build
        // time, so any sub-domain the owner creates is picked up automatically.
        manifestPlaceholders["undalWorkerHost"] = workerHost

        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLOUDINARY_CLOUD_NAME", asBuildConfigString(cloudinaryCloudName))
        buildConfigField("String", "CLOUDINARY_UPLOAD_PRESET", asBuildConfigString(cloudinaryUploadPreset))
        buildConfigField("String", "WORKER_BASE_URL", asBuildConfigString(workerBaseUrl.trimEnd('/')))
        buildConfigField("String", "WORKER_HOST", asBuildConfigString(workerHost))
        buildConfigField("String", "FIREBASE_WEB_API_KEY", asBuildConfigString(firebaseWebApiKey))
        buildConfigField("String", "SUPPORT_PHONE", asBuildConfigString(supportPhone))
        buildConfigField("String", "STORE_TIMEZONE", asBuildConfigString("Asia/Dhaka"))
        buildConfigField("String", "CURRENCY_SYMBOL", asBuildConfigString("BDT"))
        buildConfigField("String", "ORDER_SOUND_NAME", asBuildConfigString("undal_order_alert"))
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // R8 is opt-in (-PundalMinify=true) until the owner flips it after a
            // smoke test of the signed artefact. proguard-rules.pro is complete
            // and version-controlled either way.
            isMinifyEnabled = undalMinify
            isShrinkResources = undalMinify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time is used for every Dhaka-time decision and API 24/25 devices
        // do not ship it, so desugaring is mandatory for this minSdk.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

kapt {
    correctErrorTypes = true
}

// ---------------------------------------------------------------------------
// The official order sound (LAW-12) is authored at assets/sound/ and must exist
// as res/raw/undal_order_alert.mp3 for the app to reference R.raw.<name>.
// CI copies it before Gradle runs; this task keeps local builds identical.
// ---------------------------------------------------------------------------
val orderSoundSource = rootProject.layout.projectDirectory.file("assets/sound/undal_order_alert.mp3")
val syncUndalOrderSound = tasks.register<Copy>("syncUndalOrderSound") {
    group = "undal"
    description = "Copies the official Undal order sound into app/src/main/res/raw."
    val source = orderSoundSource.asFile
    onlyIf { source.exists() }
    from(source)
    into(layout.projectDirectory.dir("src/main/res/raw"))
    rename { "undal_order_alert.mp3" }
    doFirst {
        if (!source.exists()) {
            logger.warn("Undal: ${orderSoundSource.asFile} is missing — order alerts will have no sound in this build.")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(syncUndalOrderSound)
}

dependencies {
    // --- androidx core / lifecycle ------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // --- compose ------------------------------------------------------------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- navigation ---------------------------------------------------------
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // --- dependency injection ----------------------------------------------
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)

    // --- firebase -----------------------------------------------------------
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.database)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)

    // --- data / work --------------------------------------------------------
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // --- location / maps ----------------------------------------------------
    implementation(libs.play.services.location)
    implementation(libs.osmdroid.android)

    // --- images -------------------------------------------------------------
    implementation(libs.cloudinary.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // --- network ------------------------------------------------------------
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // --- coroutines ---------------------------------------------------------
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // --- java.time on API 24/25 --------------------------------------------
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- unit tests ---------------------------------------------------------
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // --- instrumented tests -------------------------------------------------
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
