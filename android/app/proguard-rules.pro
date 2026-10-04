# =========================================================================
# ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Supreme ProGuard Rules v8.0
# قواعد التعتيم والتشفير القصوى لصد الهندسة العكسية وحماية العميل العسكري
# =========================================================================

# تحسين التعتيم وإزالة الأكواد غير المستخدمة بضراوة لضمان صعوبة التحليل
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'com.uranium.core.secure'
-energetic

# تعتيم وحماية حزمة العميل الأساسية بالكامل تحت سيادة سلطان الميدان
-keep class com.uranium.fist.** { *; }
-keepclassmembers class com.uranium.fist.** { *; }
-dontwarn com.uranium.fist.**

# الحفاظ على خصائص الانعكاس والتعليقات البرمجية الحرجة
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# الحفاظ على مكتبات الشبكات والاتصال المشفر (OkHttp & Logging Interceptor)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# الحفاظ على معالجات الخيوط والمهام الخلفية (Coroutines & WorkManager)
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class androidx.work.** { *; }

# الحفاظ على نظام التشفير والأمان المتقدم
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# مسح وقطع إشارات السجلات (Logs) تماماً لمنع تسريب أي بيانات تشغيلية
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
