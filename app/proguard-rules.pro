# Methods invoked by WebView JavaScript must retain their exported names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Media3 locates the optional audio renderer by reflection.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    public <init>(...);
}

# JNI entry points and callbacks are also covered by the decoder's consumer rules.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep OTA update manager and FileProvider
-keep class com.anpinime.projector.AppUpdateManager { *; }
-keep class androidx.core.content.FileProvider { *; }
