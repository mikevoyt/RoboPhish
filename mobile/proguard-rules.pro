# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/google/home/mangini/tools/android-studio/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
-verbose

# Keep generic type signatures for Kodein TypeToken resolution.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes Kotlin.Metadata

# Kodein relies on reflection for bindings and injection.
-keep class org.kodein.di.** { *; }
-keep class org.kodein.di.android.** { *; }
-keep class org.kodein.di.jxinject.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @org.kodein.di.* *;
}

# remove code paths that has the SDK int less than 21 up to 1000
-allowaccessmodification
-assumevalues class android.os.Build$VERSION {
    int SDK_INT return 21..1000;
}
