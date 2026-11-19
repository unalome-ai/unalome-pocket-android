# JNI: the native library binds to these names, so R8 must neither remove nor rename them.
-keepclasseswithmembernames,includedescriptorclasses class ai.unalome.pocket.llamaengine.LlamaNative {
    native <methods>;
}
# Created from native code (JNI_OnLoad looks these up by name).
-keep class ai.unalome.pocket.llamaengine.NativeStep {
    <init>(int, byte[], byte[]);
}
-keep class ai.unalome.pocket.llamaengine.LlamaException {
    <init>(java.lang.String);
}
# Called from native code during a load.
-keep interface ai.unalome.pocket.llamaengine.LoadListener {
    boolean onProgress(float);
}
-keep class * implements ai.unalome.pocket.llamaengine.LoadListener {
    boolean onProgress(float);
}
