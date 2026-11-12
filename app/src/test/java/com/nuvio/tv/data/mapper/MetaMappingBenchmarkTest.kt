package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in host JVM benchmark for the metadata path (Milestone 3 evaluation in NUVIO_NEXT.md):
 * Moshi decoding of a Stremio meta response vs mapping it to domain models, for series of
 * typical and extreme episode counts. Run with
 * `./gradlew :app:testFullDebugUnitTest --tests '*MetaMappingBenchmarkTest' -Pnuvio.bench=true`.
 */
class MetaMappingBenchmarkTest {

    // Same configuration as NetworkModule.provideMoshi.
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MetaResponseDto::class.java)

    @Test
    fun `benchmark meta decoding and mapping`() {
        assumeTrue(System.getProperty("nuvio.bench") == "true")
        println("episodes,json_kb,decode_us,map_us,map_share_pct,map_no_runtime_us")
        for (episodes in listOf(10, 60, 250, 1_100)) {
            val json = seriesJson(episodes, withRuntime = true)
            val noRuntimeJson = seriesJson(episodes, withRuntime = false)
            val dto = requireNotNull(adapter.fromJson(json)?.meta)
            val noRuntimeDto = requireNotNull(adapter.fromJson(noRuntimeJson)?.meta)
            check(dto.toDomain().videos.all { it.runtime != null })

            val decode = measure { adapter.fromJson(json)!!.meta!!.id.length }
            val map = measure { dto.toDomain().videos.size }
            val mapNoRuntime = measure { noRuntimeDto.toDomain().videos.size }
            println(
                "%d,%.0f,%.1f,%.1f,%.1f,%.1f".format(
                    episodes, json.length / 1024.0, decode / 1_000.0, map / 1_000.0,
                    100.0 * map / (decode + map), mapNoRuntime / 1_000.0
                )
            )
        }
    }

    /** Median wall time in ns of one call after a 1.5 s warm-up. */
    private fun measure(block: () -> Int): Double {
        var sink = 0
        val deadline = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < deadline) sink += block()
        val samples = DoubleArray(41) {
            val start = System.nanoTime()
            sink += block()
            (System.nanoTime() - start).toDouble()
        }
        check(sink != 0)
        samples.sort()
        return samples[samples.size / 2]
    }

    companion object {
        private val runtimes = listOf("24 min", "1h 2min", "45", "58m", "1h", " 42 MIN ")

        /** A Cinemeta-shaped series response with [episodes] videos. */
        fun seriesJson(episodes: Int, withRuntime: Boolean): String = buildString {
            append("""{"meta":{"id":"tt0388629","imdb_id":"tt0388629","type":"series","name":"Benchmark Series",""")
            append(""""poster":"https://images.metahub.space/poster/medium/tt0388629/img",""")
            append(""""background":"https://images.metahub.space/background/medium/tt0388629/img",""")
            append(""""logo":"https://images.metahub.space/logo/medium/tt0388629/img",""")
            append(""""description":"${"A long-running series about a crew sailing the seas. ".repeat(8)}",""")
            append(""""releaseInfo":"1999-","runtime":"24 min","imdbRating":"8.9","status":"Continuing",""")
            append(""""genres":["Animation","Action","Adventure","Comedy","Fantasy"],""")
            append(""""cast":["Actor One","Actor Two","Actor Three","Actor Four"],"director":["Director One"],""")
            append(""""links":[""")
            (0 until 30).joinTo(this, ",") { """{"name":"Link $it","category":"Genres","url":"stremio:///discover/x/$it"}""" }
            append("""],"trailers":[{"source":"abcdefghijk","type":"Trailer"}],"videos":[""")
            (0 until episodes).joinTo(this, ",") { index ->
                val season = index / 25 + 1
                val episode = index % 25 + 1
                buildString {
                    append("""{"id":"tt0388629:$season:$episode","name":"Episode $episode title","season":$season,""")
                    append(""""episode":$episode,"number":$episode,"released":"2001-0${season % 9 + 1}-1${episode % 9}T00:00:00.000Z",""")
                    append(""""thumbnail":"https://episodes.metahub.space/tt0388629/$season/$episode/w780.jpg",""")
                    append(""""overview":"${"Something happens in this episode and the crew reacts. ".repeat(5)}",""")
                    append(""""rating":"8.${index % 10}"""")
                    if (withRuntime) append(""","runtime":"${runtimes[index % runtimes.size]}"""")
                    append("}")
                }
            }
            append("]}}")
        }
    }
}
