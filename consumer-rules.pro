# Keep @JavascriptInterface members used to bridge Longdo Map JS events back to Kotlin.
-keepclassmembers class com.mapconductor.longdo.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the Longdo Map API3 SDK (WebView-based) intact.
-keep class com.longdo.sdk3.** { *; }
-keep class com.longdo.map3.** { *; }
