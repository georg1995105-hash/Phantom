# PHANTOM ProGuard rules
-keep class libv2ray.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn org.golang.**
-dontwarn libv2ray.**
-dontwarn io.nekohasekai.**

# Gomobile JNI locates these classes/members by their original binary names.
-keep class go.** { *; }
-keep class sb.** { *; }
-keep class org.golang.** { *; }
# WorkManager persists worker class names across application upgrades.
-keep class * extends androidx.work.ListenableWorker { *; }
