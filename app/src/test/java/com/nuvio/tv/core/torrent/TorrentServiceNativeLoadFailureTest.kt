package com.nuvio.tv.core.torrent

import android.content.Context
import com.nuvio.engine.NuvioEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Simulates a device where the engine's native library cannot load (for example a partially
 * extracted install): `NuvioEngine.create` then throws `UnsatisfiedLinkError` on the first attempt
 * and `NoClassDefFoundError` afterwards, neither of which is an [Exception].
 */
class TorrentServiceNativeLoadFailureTest {

    @After
    fun tearDown() = unmockkObject(NuvioEngine.Companion)

    private fun service(): TorrentService {
        val root = Files.createTempDirectory("torrent-service-test").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { noBackupFilesDir } returns root.resolve("no-backup").apply { mkdirs() }
            every { cacheDir } returns root.resolve("cache").apply { mkdirs() }
        }
        val settings = mockk<TorrentSettings> {
            every { this@mockk.settings } returns flowOf(TorrentSettingsData())
        }
        return TorrentService(context, settings)
    }

    @Test
    fun `engine load failure surfaces as a catchable exception and an error state`() = runBlocking {
        mockkObject(NuvioEngine.Companion)
        every { NuvioEngine.create(any(), any()) } throws
            UnsatisfiedLinkError("dlopen failed: library \"libnuvio_engine.so\" not found") andThenThrows
            NoClassDefFoundError("Could not initialize class com.nuvio.engine.internal.NativeBridge")
        val service = service()
        repeat(2) { attempt ->
            try {
                service.startStream(infoHash = "0123456789abcdef0123456789abcdef01234567", fileIdx = 0)
                fail("attempt $attempt: expected the start to fail")
            } catch (error: Exception) {
                assertTrue("attempt $attempt: ${error.javaClass}", error is IllegalStateException)
                // Coroutine stack-trace recovery may wrap the exception in a copy, so search the chain.
                val causes = generateSequence(error.cause) { it.cause }.toList()
                assertTrue("attempt $attempt: causes $causes", causes.any { it is LinkageError })
            }
            assertTrue("attempt $attempt: ${service.state.value}", service.state.value is TorrentState.Error)
        }
        assertEquals(
            TorrentService.ENGINE_UNAVAILABLE_MESSAGE,
            (service.state.value as TorrentState.Error).message
        )
    }
}
