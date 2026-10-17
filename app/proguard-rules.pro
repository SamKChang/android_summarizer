# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gist.app.**$$serializer { *; }
-keepclassmembers class com.gist.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.gist.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink
-dontwarn com.google.crypto.tink.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}

# Readability4J / jsoup
-dontwarn org.slf4j.**
-dontwarn net.dankito.readability4j.**
