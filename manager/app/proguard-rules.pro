# 洛茜工具箱 ProGuard 规则
# 保留 Natives 类（JNI name-based 绑定依赖原始类名/方法名）
-keep class me.weishu.kernelsu.Natives { *; }
-keep class me.weishu.kernelsu.Natives$* { *; }
-keep class me.weishu.kernelsu.Natives$Profile$* { *; }

# 保留 magica 包（AppZygotePreload / MagicaService 等 manifest 引用的类）
-keep class me.weishu.kernelsu.magica.** { *; }

# 保留 BuildConfig
-keep class me.weishu.kernelsu.BuildConfig { *; }

# 保留 Shizuku SDK（ContentProvider / Binder 接口需要原始类名）
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# 保留所有带 native 方法的类
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 Parcelable Creator
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 保留 Kotlin Metadata（反射 / 序列化需要）
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保留 kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
