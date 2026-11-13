# R8 rules for the release variant.
#
# The app is the host for *native extensions*: a first-party pack ships a dex that is loaded into
# QCode's own process with QCode's class loader as its parent, and its bytecode names host classes
# directly. R8 cannot see those references, so anything a pack links against has to keep its name
# and members — otherwise an installed extension dies with NoClassDefFoundError on its first frame
# even though the app itself is fine.
#
# Three groups are kept:
#   * the promised ABI (":core:ext-api"), which `QCODE_EXT_ABI` versions;
#   * the surface the specs call "reachable but not promised": `:core:design` and the adb package;
#   * the shared runtime those packs compile against — Kotlin, coroutines and Compose — because a
#     Kotlin extension's generated code calls `kotlin.jvm.internal.Intrinsics` and Compose's
#     generated lambda classes by name, in *its* dex, not ours.
#
# Keeping them means R8 shrinks and renames the IDE's own code, which is the bulk of the dex, while
# the extension-facing classes stay stable. New ABI surface must be added here, or the pack must be
# rebuilt and `QCODE_EXT_ABI` bumped.

-keep class dev.blamspot.jcode.ext.api.** { *; }
-keep class dev.blamspot.jcode.design.** { *; }
-keep class dev.blamspot.jcode.core.distro.adb.** { *; }

-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }

# The browser passes these objects to `addJavascriptInterface`; the page calls the methods by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Kotlin top-level functions and object singletons are reached through their file/object class names
# from extension bytecode; the rule above keeps those classes whole.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters
