-dontobfuscate

# JNI surface: these classes/members are looked up by name from native code (FindClass/GetMethodID/ThrowNew),
# which R8 cannot see. Most are annotated with @Keep already; NativeException (thrown from C++) is not.
# Keeping the whole package costs nothing measurable and makes the Release (shrunk) build safe.
-keep class info.cemu.cemu.nativeinterface.** { *; }
