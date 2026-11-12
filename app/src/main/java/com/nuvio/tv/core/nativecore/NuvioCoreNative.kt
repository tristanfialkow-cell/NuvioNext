package com.nuvio.tv.core.nativecore

/**
 * JNI surface of `libnuvio_core.so`, built from `rust/nuvio-core-jni`. Use [NuvioCore] and the
 * feature wrappers rather than calling these directly; they validate the ABI version first.
 *
 * Every function is pure and thread-safe and never calls back into Java. Invalid arguments throw
 * [IllegalArgumentException]; an internal Rust panic surfaces as [IllegalStateException].
 */
internal object NuvioCoreNative {
    @JvmStatic
    external fun nativeAbiVersion(): Int

    @JvmStatic
    external fun nativeVersion(): String

    /** Name tables used to verify enum agreement; see `nativeEnumTable` in the Rust crate. */
    @JvmStatic
    external fun nativeEnumTable(kind: Int): Array<String>

    /** Batch release classification; layout documented in `NativeReleaseFacts`. */
    @JvmStatic
    external fun nativeClassifyReleases(
        layout: IntArray,
        strings: Array<String?>,
        out: IntArray,
        languagesOut: IntArray,
        groupsOut: Array<String?>
    ): Int
}
