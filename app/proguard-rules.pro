# ---- Glance ActionCallback ----
# Glance 用反射 new 出 ActionCallback（小工具按鈕的動作）。R8 full mode 下，Glance 自帶的
# "-keep class * extends ActionCallback" 不會保留無參數建構子，按鈕就靜靜地失效。
-keepclassmembers class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}

# ---- kotlinx.serialization (scoped to the app package, minimal keep) ----
# Generated serializers are referenced when a class is serialized; without these,
# R8 can strip the Companion/$$serializer and serialization fails at runtime with
# "Serializer for class 'X' is not found".
-keepclassmembers @kotlinx.serialization.Serializable class com.kmnexus.codexmeter.** {
    *** Companion;
    *** INSTANCE;
}
-if @kotlinx.serialization.Serializable class com.kmnexus.codexmeter.**
-keepclassmembers class com.kmnexus.codexmeter.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kmnexus.codexmeter.**$$serializer { *; }

# Runtime annotation metadata used by serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
