# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aiglobal.jobfinder.** {
    *** Companion;
}
-keepclasseswithmembers class com.aiglobal.jobfinder.** {
    kotlinx.serialization.KSerializer serializer(...);
}
