# The app stays intact. R8 still strips unused library code, and resource shrinking
# drops unused resources. Native code is limited to the ABI split.
-keep class org.visualaid.obstacleguide.** { *; }

-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.task.** { *; }
-dontwarn org.tensorflow.**

-keep class org.vosk.** { *; }
-keep class com.alphacephei.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

-keep class com.google.ar.** { *; }
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.ar.**

-keep class androidx.camera.** { *; }
-keep class androidx.camera.camera2.impl.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}
