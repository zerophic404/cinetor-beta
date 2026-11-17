# ---- Metadata & stack-trace attributes (R8 full mode; mapping.txt uploaded as CI artifact) ----
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, SourceFile, LineNumberTable, Exceptions
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization (R8 full-mode safe across all app packages + Navigation 2.8 routes) ----
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mythbrix.cinetor.**$$serializer { *; }
-keepclassmembers class com.mythbrix.cinetor.** {
    *** Companion;
}
-keepclasseswithmembers class com.mythbrix.cinetor.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static kotlinx.serialization.KSerializer serializer(...);
    <fields>;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.* <fields>;
}
# Type-safe navigation routes (including @Serializable data object) + settings/profile/gateway/music/comics models.
-keep @kotlinx.serialization.Serializable class com.mythbrix.cinetor.** { *; }
-keepclassmembers enum com.mythbrix.cinetor.** { *; }

# ---- Chromeless YouTube WebView bridge (@JavascriptInterface on phone & Android TV) ----
# Prevent R8 full mode from stripping, renaming, privatizing, or merging AndroidBridge / YouTubeAndroidBridge
# callbacks invoked from the embedded chromeless YouTube IFrame player WebView.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @android.webkit.JavascriptInterface *;
}
-keep class com.mythbrix.cinetor.feature.player.** {
    @android.webkit.JavascriptInterface *;
}

# ---- Explicit @Keep annotations ----
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ---- Media3 / ExoPlayer / FFmpeg / FLAC / HLS / DASH / SS / RTSP reflective entrypoints ----
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.flac.** { *; }
-keep class androidx.media3.extractor.flac.** { *; }
-dontwarn androidx.media3.decoder.**
-keepclassmembers class androidx.media3.exoplayer.dash.DashMediaSource$Factory {
    public <init>(androidx.media3.datasource.DataSource$Factory);
}
-keepclassmembers class androidx.media3.exoplayer.hls.HlsMediaSource$Factory {
    public <init>(androidx.media3.datasource.DataSource$Factory);
}
-keepclassmembers class androidx.media3.exoplayer.smoothstreaming.SsMediaSource$Factory {
    public <init>(androidx.media3.datasource.DataSource$Factory);
}
-keepclassmembers class androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory {
    public <init>();
}

# ---- Haze 1.1.1 & Android 12+ RenderEffect / RuntimeShader (safe on API 24–30 phone & Android TV) ----
# Prevent R8 full mode from inlining/merging API 31+ RenderEffect/RenderNode helpers into API 24–30 classes.
-keep class dev.chrisbanes.haze.** { *; }
-dontwarn android.graphics.RenderEffect
-dontwarn android.graphics.RenderNode
-dontwarn android.graphics.RuntimeShader

# ---- Room, WorkManager, OkHttp & Coil ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class coil3.network.okhttp.** { *; }
-dontwarn okhttp3.internal.platform.**
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
