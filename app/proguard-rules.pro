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

# 2.0-pdf: PdfBox-Android without BouncyCastle (only the public-key security handler needs it,
# and that handler then fails with an error inside the :pdf process instead of loading).
-dontwarn org.bouncycastle.**
# JPEG 2000 (JPXFilter) needs the optional jp2-android decoder, which we do not ship; PdfBox
# only decodes images when rendering, and lossless page copies never decode them.
-dontwarn com.gemalto.jp2.**
