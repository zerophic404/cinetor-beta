# kotlinx.serialization (R8-safe models)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.mythbrix.cinetor.**$$serializer { *; }
-keepclassmembers class com.mythbrix.cinetor.** { *** Companion; }
-keepclasseswithmembers class com.mythbrix.cinetor.** { kotlinx.serialization.KSerializer serializer(...); }
