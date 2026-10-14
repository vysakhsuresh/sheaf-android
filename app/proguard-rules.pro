-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }

# BouncyCastle finds its algorithm classes by name; stripping or renaming them breaks the
# signature it makes without a compile error to say so.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
