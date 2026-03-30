# Add project specific ProGuard rules here.

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.cellomusic.app.**$$serializer { *; }
-keepclassmembers class com.cellomusic.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.cellomusic.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Navigation
-keep class androidx.navigation.** { *; }

# Coil
-dontwarn coil.**

# JTransforms
-keep class org.jtransforms.** { *; }
-dontwarn org.jtransforms.**

# pdfbox
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
