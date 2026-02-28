# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class io.github.reality2_roycdavies.cosmickonnect.**$$serializer { *; }
-keepclassmembers class io.github.reality2_roycdavies.cosmickonnect.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.reality2_roycdavies.cosmickonnect.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
