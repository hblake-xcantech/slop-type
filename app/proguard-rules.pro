# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# Slop Type: keep engine/app classes (reflection in DaemonReceiver, serialization)
-keep class dev.sloptype.** { *; }
-keepclassmembers class dev.sloptype.** { *; }
-dontwarn dev.sloptype.**

# WorkManager / Room: generated classes are reflectively constructed
-keep class androidx.work.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.startup.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class dev.sloptype.** { *** Companion; }
