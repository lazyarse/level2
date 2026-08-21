# MediaPipe Tasks ships no consumer ProGuard rules (verified in the 1.0.0
# AAR); its JNI layer resolves Java classes and protobuf-lite generated code
# reflectively, so aggressive shrinking breaks engine init at runtime.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# LiteRT (org.tensorflow.lite) ships its own consumer rules; models load via
# AssetManager file paths, so nothing app-side needs keeping.
