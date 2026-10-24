# kotlinx.serialization: keep generated serializers for @Serializable DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.hearth.casaos.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.hearth.casaos.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
