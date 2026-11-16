package info.cemu.cemu.nativeinterface

import androidx.annotation.Keep

// Instantiated only from native code (JNIUtils ThrowNew): keep it from being shrunk by R8.
@Keep
class NativeException(message: String) : RuntimeException(message)
