# kotlinx.serialization: keep the generated serializers of the state classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class io.github.pyp6.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.pyp6.**$$serializer { *; }
-keepclassmembers class io.github.pyp6.** {
    *** Companion;
}
# XZ (waveform packs) uses no reflection, but its optional classes are absent.
-dontwarn org.tukaani.xz.**
