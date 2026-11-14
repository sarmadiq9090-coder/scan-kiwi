# Tesseract4Android / Leptonica use JNI: native code reads Java fields and calls
# Java methods by name, so these classes must not be renamed or stripped.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.googlecode.**

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
