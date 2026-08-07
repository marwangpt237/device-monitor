# Keep AccessibilityService / DeviceAdmin components (referenced from manifest)
-keep class com.workmonitor.KeystrokeAccessibilityService { *; }
-keep class com.workmonitor.DeviceAdminReceiver { *; }
-dontwarn javax.annotation.**

# Retrofit/okhttp not used yet; strip debug logging in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}