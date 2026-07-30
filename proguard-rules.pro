# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# The Longdo Map API3 SDK is WebView-based and communicates through @JavascriptInterface
# members. Keep our bridge and the SDK classes so they survive shrinking/obfuscation.
-keepclassmembers class com.mapconductor.longdo.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.longdo.sdk3.** { *; }
-keep class com.longdo.map3.** { *; }

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable
