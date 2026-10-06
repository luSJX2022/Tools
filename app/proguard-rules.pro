# R8 混淆规则：只保留运行时按名字反射查找的东西，其余全部压缩。

# kotlinx.serialization：$$serializer 是编译期生成的，运行时经 Companion 按名查找
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.qzkt.timetable.**$$serializer { *; }
-keepclassmembers class com.qzkt.timetable.** {
    *** Companion;
}
-keepclasseswithmembers class com.qzkt.timetable.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 应用内登录（WebImportScreen）通过 @JavascriptInterface 把 cookie 传回 App
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
