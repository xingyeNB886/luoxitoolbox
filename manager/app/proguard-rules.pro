# 洛茜工具箱 ProGuard 规则
# Application：入口类 + 顶层 ksuApp 属性不能被混淆 / 删除
-keep class me.weishu.kernelsu.KernelSUApplication { *; }
-keep class me.weishu.kernelsu.KernelSUApplication$* { *; }
-keepnames class **.KernelSUApplication { *; }
# 顶层 ksuApp 属性 / 方法由 Kotlin 生成在 KernelSUApplicationKt，也保留
-keep class me.weishu.kernelsu.KernelSUApplicationKt { *; }

# 保留 Natives 类（JNI name-based 绑定依赖原始类名/方法名）
-keep class me.weishu.kernelsu.Natives { *; }
-keep class me.weishu.kernelsu.Natives$* { *; }
-keep class me.weishu.kernelsu.Natives$Profile$* { *; }

# 崩溃处理器 + 崩溃 Activity 不允许被混淆
-keep class me.weishu.kernelsu.ui.crash.** { *; }

# PermissionManager / 权限相关工具类保留（被 Manifest / Shizuku 反射引用）
-keep class me.weishu.kernelsu.ui.util.PermissionManager { *; }
-keep class me.weishu.kernelsu.ui.util.PermissionManager$* { *; }
-keep class me.weishu.kernelsu.ui.util.PermissionGrantType { *; }
-keep class me.weishu.kernelsu.ui.util.PermissionGrantType$* { *; }

# 保留 BuildConfig
-keep class me.weishu.kernelsu.BuildConfig { *; }

# 保留 Shizuku SDK（ContentProvider / Binder 接口需要原始类名）
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# 保留 Shizuku UserService 的 AIDL 接口和实现类（跨进程 Binder 按类名反序列化，混淆即失效）
-keep class me.weishu.kernelsu.service.IShellService { *; }
-keep class me.weishu.kernelsu.service.IShellService$* { *; }
-keep class me.weishu.kernelsu.service.ShellService { *; }

# 保留 HiddenApiBypass（被 attachBaseContext 反射调用）
-keep class org.lsposed.hiddenapibypass.** { *; }

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
