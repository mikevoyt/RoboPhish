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

# remove code paths that has the SDK int less than 21 up to 1000
-allowaccessmodification
-assumevalues class android.os.Build$VERSION {
    int SDK_INT return 21..1000;
}

