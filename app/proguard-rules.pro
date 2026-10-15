# pdfbox-android references an optional JPEG-2000 decoder that is not bundled.
-dontwarn com.gemalto.jp2.JP2Decoder

# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aiglobal.jobfinder.** {
    *** Companion;
}
-keepclasseswithmembers class com.aiglobal.jobfinder.** {
    kotlinx.serialization.KSerializer serializer(...);
}
