# S10: release builds carry no log calls, so no file names or paths reach logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# PDFBox-Android: optional JPEG 2000 support (com.gemalto.jp2) is not bundled; JPX images report an error instead.
-dontwarn com.gemalto.jp2.**
