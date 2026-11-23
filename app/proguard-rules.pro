-keep class com.acid.wechat.HookEntry { *; }
-keep class org.luckypray.dexkit.** { *; }
-keep class me.yun.silk.SilkCodec { *; }
-keepattributes JavascriptInterface,Annotation
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn de.robv.android.xposed.**
