# ---------------------------------------------------------------------------
# Undal HomeKitchen — R8 / ProGuard contract
#
# R8 is opt-in (-PundalMinify=true). Rules below are complete for the whole
# stack so the switch is a one-line change after a signed smoke test.
# ---------------------------------------------------------------------------

# --- keep the app entry points ---------------------------------------------
-keep class com.undal.syl.app.UndalApp { *; }
-keep class com.undal.syl.app.MainActivity { *; }

# --- Firebase / Play services ----------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Firestore serialises model classes reflectively.
-keepclassmembers class com.undal.syl.app.data.dto.** {
    <init>();
    <fields>;
    <methods>;
}

# --- Hilt / Dagger ----------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-dontwarn dagger.hilt.**

# --- Kotlin coroutines ------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# --- osmdroid (reflective tile providers) ----------------------------------
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# --- Cloudinary -------------------------------------------------------------
-keep class com.cloudinary.** { *; }
-dontwarn com.cloudinary.**

# --- OkHttp / Coil ----------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil3.**

# --- keep annotations used by Compose compiler ------------------------------
-keep @androidx.compose.runtime.Composable class * { *; }

# --- keep native/JNI hooks (silences warnings on stripped builds) ----------
-keepclasseswithmembernames class * {
    native <methods>;
}
