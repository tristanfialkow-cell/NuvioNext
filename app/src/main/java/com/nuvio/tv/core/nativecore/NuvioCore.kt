package com.nuvio.tv.core.nativecore

import android.util.Log

/**
 * Loads the Rust core library once, on first use, and checks it implements the contract this
 * build expects. Loading never throws: a missing library, unsupported ABI or version mismatch
 * leaves the core [Status.Unavailable] and callers keep using their Kotlin implementations.
 */
object NuvioCore {
    /** Must equal `nuvio_core::ABI_VERSION` in `rust/nuvio-core/src/lib.rs`. */
    const val EXPECTED_ABI_VERSION = 1
    const val LIBRARY_NAME = "nuvio_core"

    /** JVM unit tests point this system property at a host build of the library. */
    internal const val HOST_LIBRARY_PROPERTY = "nuvio.core.hostLib"

    private const val TAG = "NuvioCore"

    sealed interface Status {
        data class Available(val version: String, val abiVersion: Int) : Status
        data class Unavailable(val reason: String) : Status
    }

    val status: Status by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load() }

    val isAvailable: Boolean
        get() = status is Status.Available

    private fun load(): Status {
        val result = try {
            val hostLibrary = System.getProperty(HOST_LIBRARY_PROPERTY)
            if (hostLibrary.isNullOrBlank()) System.loadLibrary(LIBRARY_NAME) else System.load(hostLibrary)
            val abiVersion = NuvioCoreNative.nativeAbiVersion()
            if (abiVersion != EXPECTED_ABI_VERSION) {
                Status.Unavailable("ABI version $abiVersion, expected $EXPECTED_ABI_VERSION")
            } else {
                Status.Available(NuvioCoreNative.nativeVersion(), abiVersion)
            }
        } catch (error: Throwable) {
            // UnsatisfiedLinkError (missing .so or symbol), SecurityException, etc.
            if (error is VirtualMachineError) throw error
            Status.Unavailable("${error.javaClass.simpleName}: ${error.message}")
        }
        when (result) {
            is Status.Available -> Log.i(TAG, "Rust core ${result.version} loaded (ABI ${result.abiVersion})")
            is Status.Unavailable -> Log.w(TAG, "Rust core unavailable: ${result.reason}")
        }
        return result
    }
}
