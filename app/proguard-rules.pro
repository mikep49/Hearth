# kotlinx.serialization: keep generated serializers for @Serializable DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.hearth.casaos.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.hearth.casaos.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JSch instantiates its ciphers, key exchanges and MACs by class name from its config.
-keep class com.jcraft.jsch.** { *; }
# Optional JSch integrations (JNA, logging backends) that the app does not ship.
-dontwarn com.jcraft.jsch.**
# Bouncy Castle is shipped for Ed25519 keys; JSch reaches it through the classes kept above. The rest of it
# refers to desktop Java classes Android lacks.
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.net.unix.**

# Glance creates widget button callbacks (e.g. the refresh button) by class name, with no arguments.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
