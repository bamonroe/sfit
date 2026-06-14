# Keep kotlinx.serialization generated serializers for our @Serializable models.
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclasseswithmembers class net.bam.sfit.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
