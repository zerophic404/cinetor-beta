# kotlinx.serialization (R8-safe models)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.mythbrix.cinetor.**$$serializer { *; }
-keepclassmembers class com.mythbrix.cinetor.** { *** Companion; }
-keepclasseswithmembers class com.mythbrix.cinetor.** { kotlinx.serialization.KSerializer serializer(...); }

# ---- Task 7.1 (R8 full mode) ----
# Readable crash stacks (mapping.txt uploaded as CI artifact).
-keepattributes Signature, SourceFile, LineNumberTable, Exceptions
-renamesourcefileattribute SourceFile
# Type-safe navigation routes + settings/profile/TMDB models are (de)serialized by kotlinx.serialization.
-keep @kotlinx.serialization.Serializable class com.mythbrix.cinetor.** { *; }
-keepclassmembers enum com.mythbrix.cinetor.** { *; }
# Room (generated _Impl) / Media3 / WorkManager / OkHttp ship their own consumer rules.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Hardening §2 (docs/requirements-hardening.md): no endpoint leakage in release ----
# Strip every android.util.Log call from release bytecode so request URLs / headers / stream URLs
# can never reach logcat on a shipped APK. Developer detail lives in the in-memory DebugLog only.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}
