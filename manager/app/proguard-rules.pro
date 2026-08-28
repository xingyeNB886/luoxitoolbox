-verbose
-optimizationpasses 5

-dontwarn org.conscrypt.**
-dontwarn kotlinx.serialization.**

# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.google.auto.service.AutoService
-dontwarn com.google.j2objc.annotations.RetainedWith
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.AnnotationMirror
-dontwarn javax.lang.model.element.AnnotationValue
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.ElementVisitor
-dontwarn javax.lang.model.element.ExecutableElement
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.element.Name
-dontwarn javax.lang.model.element.PackageElement
-dontwarn javax.lang.model.element.TypeElement
-dontwarn javax.lang.model.element.TypeParameterElement
-dontwarn javax.lang.model.element.VariableElement
-dontwarn javax.lang.model.type.ArrayType
-dontwarn javax.lang.model.type.DeclaredType
-dontwarn javax.lang.model.type.ExecutableType
-dontwarn javax.lang.model.type.TypeKind
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVariable
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.AbstractAnnotationValueVisitor8
-dontwarn javax.lang.model.util.AbstractTypeVisitor8
-dontwarn javax.lang.model.util.ElementFilter
-dontwarn javax.lang.model.util.Elements
-dontwarn javax.lang.model.util.SimpleElementVisitor8
-dontwarn javax.lang.model.util.SimpleTypeVisitor7
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn javax.lang.model.util.Types
-dontwarn javax.tools.Diagnostic$Kind


# MMRL:webui reflection
-keep class com.dergoogler.mmrl.webui.model.ModId { *; }
-keep class com.dergoogler.mmrl.webui.interfaces.** { *; }
-keep class com.sukisu.ultra.ui.webui.WebViewInterface { *; }

-keep,allowobfuscation class * extends com.dergoogler.mmrl.platform.content.IService { *; }

# Shizuku / Sui（Provider/Binder 按名反序列化，混淆即失效）
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }

# Shizuku UserService（跨进程 Binder 按类名反序列化）
-keep class com.sukisu.ultra.service.IShellService { *; }
-keep class com.sukisu.ultra.service.ShellService { *; }

# Shizuku 授权监听（反射回调）
-keep class com.sukisu.ultra.ui.util.PermissionManager { *; }