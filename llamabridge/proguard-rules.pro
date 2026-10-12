# JNI entry points are resolved by name at runtime; keep them through R8 in consuming apps.
-keepclasseswithmembernames class dev.sloptype.llamabridge.LlamaNative { native <methods>; }
-keep class dev.sloptype.llamabridge.LlamaNative { *; }
