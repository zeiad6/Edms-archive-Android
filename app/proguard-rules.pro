# ─────────────────────────────────────────────────────────────────────────────
# R8 rules — the release APK must not be a readable copy of the sources.
#
# The desktop build ships JavaScript, so anyone can read the whole app. The
# Android build has to survive the same scrutiny: after R8 the class names are
# single letters, the method names are unreadable, the source/line attributes
# are gone and the resource table is renamed. Reversing it still takes real
# effort — the keep rules below are the only places where names survive.
# ─────────────────────────────────────────────────────────────────────────────

# Shrink harder than the default. 5 passes is past the point of diminishing
# returns, costs a few seconds and reliably removes more dead code.
-optimizationpasses 5

# Let R8 rewrite access modifiers so it can inline and merge more aggressively.
-allowaccessmodification

# Collapse every class into one flat package, so the DEX carries no
# com/zidex/edms/... structure hinting at what each class does.
-repackageclasses 'a'

# Adapt (fold + re-encode) compile-time string constants, so the literal URLs
# and resource paths that live in the code do not survive as plain text.
-adaptclassstrings

# No source file names, no line numbers, no source-level signatures in the DEX:
# those are the first thing a decompiler uses to rebuild readable code.
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,Exceptions

# Library keep rules routinely reference classes that are only present on other
# platforms. A missing class here is a warning, not a build failure.
-dontwarn **

# ── Keep rules (the only names that survive) ─────────────────────────────────

# JS bridge methods are called by name from the web page the paired desktop
# serves. Renaming any of them silently breaks the remote mode. The class itself
# is reached through the "EdmsAndroid" handle, so its name can still be hidden.
-keep,allowobfuscation,allowoptimization class com.zidex.edms.remote.RemoteActivity$WebBridge {
    @android.webkit.JavascriptInterface <methods>;
}
# Generic safety net for any other @JavascriptInterface class added later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Components the system instantiates by name. R8 rewrites the manifest to match
# the new names, so these stay functional while still being obfuscated.
-keep,allowobfuscation class * extends android.app.Activity
-keep,allowobfuscation class * extends androidx.activity.ComponentActivity
-keep,allowobfuscation class * extends android.app.Application
-keep,allowobfuscation class * extends android.app.Service
-keep,allowobfuscation class * extends android.content.BroadcastReceiver
-keep,allowobfuscation class * extends android.content.ContentProvider

# Views inflated by name from XML must keep their class name (app/src/main/res/
# layout/view_credits.xml). Everything else in the view tree is free to shrink.
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Anything annotated @Keep is kept whole, by the annotation's own contract.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers,allowobfuscation class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# Kotlin coroutines read these off the continuation at runtime.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembers class kotlin.coroutines.SafeContinuation { <fields>; }
-dontwarn kotlinx.coroutines.**

# ML Kit scanner/barcode models ship unbundled (Play services downloads them),
# so only the entry points we call need to survive.
-keep,allowoptimization class com.google.mlkit.vision.documentscanner.** { *; }
-keep,allowoptimization class com.google.mlkit.vision.codescanner.** { *; }
-keep,allowoptimization class com.google.mlkit.vision.barcode.** { *; }

# ── Never shipped ────────────────────────────────────────────────────────────
# Logging and print calls are removed from the release DEX, along with the
# string literals only they used.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
-assumenosideeffects class java.io.PrintStream {
    public static void print*(...);
    public static void println*(...);
}
-assumenosideeffects class kotlin.io.ConsoleKt {
    public static void print*(...);
}
