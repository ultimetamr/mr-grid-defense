# Preserve metadata used by Kotlin, coroutines, reflection and Android lifecycle factories.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

# PICO Spatial SDK crosses JNI/Binder/reflection boundaries. Keep its public runtime names.
-keep class com.pico.** { *; }
-keep interface com.pico.** { *; }
-dontwarn com.pico.**

# Immutable game/save/event models may be serialized, reflected or inspected by tooling.
-keep class com.picoxr.mrspacetowerdefense.model.** { *; }
-keep class com.picoxr.mrspacetowerdefense.event.** { *; }

# Preserve custom Android Views and XML callbacks if new ones are added later.
-keep public class * extends android.view.View { public <init>(...); }
-keepclassmembers class * extends android.view.View {
    public void *(android.view.View);
}

# Lifecycle/ViewModel constructors are located by AndroidX factories.
-keep class * extends androidx.lifecycle.ViewModel { public <init>(...); }
-keep class * implements androidx.lifecycle.LifecycleObserver { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.core.**
-dontwarn androidx.lifecycle.**

# Coroutine continuations/debug metadata are inspected by the runtime.
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class ** extends kotlin.coroutines.jvm.internal.ContinuationImpl { *; }
-dontwarn kotlinx.coroutines.**

# Keep useful release crash line mappings while hiding original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
