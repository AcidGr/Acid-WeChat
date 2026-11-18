-keep class com.acid.wechat.HookEntry { *; }
-keep class org.luckypray.dexkit.** { *; }
-keepattributes JavascriptInterface,Annotation
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn de.robv.android.xposed.**
