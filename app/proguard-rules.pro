# ML Kit finds its component registrars by name. Each one is the key of a <meta-data> entry under
# MlKitComponentDiscoveryService in the merged manifest - CommonComponentRegistrar,
# VisionCommonRegistrar and TextRegistrar - and MlKitContext loads them with Class.forName at
# process start. R8 cannot see a class name that only exists as a string in a manifest, so without
# this OCR fails on a release build and only on a release build.
#
# Nothing else in com.google.mlkit is reached by name: the rest arrives through ordinary references
# from MlKitOcrEngine, and ML Kit, Play services, Room, WorkManager and PDFBox all ship consumer
# rules of their own for what they do reflect on. A blanket keep over the SDK bought nothing but
# dex.
-keep class com.google.mlkit.**Registrar { *; }

# The bundled recogniser's own aar keeps the fields of its generated protos because the native
# pipeline reads them reflectively. The shaded package beside it holds 583 more already-minified
# classes and ships no rules at all, so the same line is held here by hand - fields by name, while
# R8 stays free to drop and rename everything it can prove is unreachable.
-keepclassmembers class com.google.android.gms.internal.mlkit_vision_text_common.** { <fields>; }

# PDFBox's JPXFilter calls into com.gemalto.jp2, the JPEG 2000 codec, which is a separate
# artifact this app does not depend on. R8 treats a missing class as a build failure, so
# without this the release build stops here while the debug build never notices.
#
# Not an oversight being papered over: nothing changes at runtime. A JPX-encoded image inside
# a PDF is undecodable in this app today, in debug exactly as in release, and the alternative
# is taking on a native codec for an encoding almost nothing produces. If JPEG 2000 ever has
# to be supported, the fix is the com.gemalto.jp2:jp2-android dependency and deleting this.
-dontwarn com.gemalto.jp2.**
