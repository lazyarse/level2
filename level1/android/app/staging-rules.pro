# Staging-only rules: instrumentation (androidTest APK, itself R8-processed)
# links into app-APK classes by original name, so under staging nothing that
# tests or androidx.test touch may be renamed, merged away, or dropped.
# Shrinking of genuinely unreachable third-party code still applies — which is
# exactly the regression class this build type exists to catch (MediaPipe).
-dontobfuscate

# MediaPipe's internals use flogger's FluentLogger.forEnclosingClass(), which
# walks the stack for its caller — R8's optimizer reshapes frames and breaks
# it. Staging stays shrink-only.
-dontoptimize
-keep class com.google.common.flogger.** { *; }

# Cross-APK link surface: kotlin stdlib + coroutines (test code), androidx.test
# support (tracing called from AndroidJUnitRunner), the app's own classes, and
# libraries appearing in app-facing signatures the tests construct.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.tracing.** { *; }
-keep class io.securitycam.level1.** { *; }
-keep class okhttp3.** { *; }
