# TempoBox release shrinking rules.

# jaudiotagger reflects over its own field/frame classes.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
# jaudiotagger references java.awt on desktop paths we never call.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**

# kotlinx-serialization: keep serializers for our @Serializable models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.tempobox.** {
    *** Companion;
}
-keepclasseswithmembers class com.tempobox.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp platform warnings
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
