# Preserve metadata used by Kotlin, Room and generated serializers while allowing
# application code to be optimized and obfuscated.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# WorkManager persists worker class names between process launches.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Kotlin Serialization generates these classes and may resolve them through companions.
-keepclassmembers class com.ds.localtaskmanager.backup.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.ds.localtaskmanager.backup.**$$serializer { *; }
