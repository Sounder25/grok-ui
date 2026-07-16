# ONNX Runtime uses JNI + reflection for its Java bindings.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# PDFBox-Android reflects into font/resource handling.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

-keepclasseswithmembernames class * {
    native <methods>;
}
