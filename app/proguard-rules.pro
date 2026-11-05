# Segu-Luky release rules (R8)

# Kotlin coroutines + Compose ship their own consumer keep rules.

# Keep accessibility service metadata components (manifest keep rules already handle them).

# Compose lifecycle composition locals
-keep,allowobfuscation class * extends androidx.compose.runtime.CompositionLocal
-keep class * {
    public static final *** INSTANCE;
}
-keepclassmembers class **.LocalLifecycleOwner {
    *;
}
-keep class androidx.lifecycle.compose.LocalLifecycleOwner { *; }
-keep class androidx.compose.ui.platform.LocalLifecycleOwner { *; }
-dontwarn androidx.lifecycle.compose.**

