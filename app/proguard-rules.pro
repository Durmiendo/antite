# The engine package is loaded at run time and links against these shell classes, which R8 cannot see.
-keep class dev.antite.engine.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.annotation.** { *; }
-keep class androidx.collection.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.media3.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class org.yaml.snakeyaml.** { *; }

# GeckoView classes come with the engine, the shell manifest only names them.
-dontwarn org.mozilla.**
-dontwarn java.beans.**
