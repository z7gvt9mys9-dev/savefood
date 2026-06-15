# kotlinx.serialization — keep @Serializable metadata and generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class kz.savefood.app.**$$serializer { *; }
-keepclassmembers class kz.savefood.app.** {
    *** Companion;
}
-keepclasseswithmembers class kz.savefood.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface retrofit2.* { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
