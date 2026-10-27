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
# Optional JSch integrations (Bouncy Castle, JNA, logging backends) that the app does not ship.
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.net.unix.**
