// ---------------------------------------------------------------------------
// Undal HomeKitchen — root build script
// All versions live in gradle/libs.versions.toml (single source of truth).
// ---------------------------------------------------------------------------

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.firebase.perf) apply false
}

tasks.register("undalInfo") {
    group = "undal"
    description = "Prints the Undal build identity (package, SDK levels, pinned versions)."
    doLast {
        logger.lifecycle(
            """
            |Undal HomeKitchen build identity
            |  package        : ${libs.versions.packageName.get()}
            |  AGP            : ${libs.versions.agp.get()}
            |  Kotlin         : ${libs.versions.kotlin.get()}
            |  Firebase BoM   : ${libs.versions.firebaseBom.get()}
            |  Compose BoM    : ${libs.versions.composeBom.get()}
            |  min/target SDK : ${libs.versions.minSdk.get()} / ${libs.versions.targetSdk.get()}
            |  timezone       : Asia/Dhaka (fixed, no DST)
            |  currency       : BDT — stored as Long paisa (100 paisa = 1 taka)
            """.trimMargin()
        )
    }
}
