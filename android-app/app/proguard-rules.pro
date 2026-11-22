# Keep kotlinx.serialization models & companion serializers under R8
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn kotlinx.serialization.**
-keep,includedescriptorclasses class io.github.fu.avbforge.data.**$$serializer { *; }
-keepclassmembers class io.github.fu.avbforge.data.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.fu.avbforge.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
