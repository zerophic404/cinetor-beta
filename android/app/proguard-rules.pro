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
