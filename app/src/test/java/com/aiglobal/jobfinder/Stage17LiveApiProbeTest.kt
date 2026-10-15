package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.ai.NvidiaAiClient
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.HttpJsonFeed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 17 live API probe.
 *
 * This is **not** part of the normal unit suite: it makes real calls to NVIDIA and Adzuna
 * and is skipped unless `LIVE_PROBE=1` is set. It exists so the "the app can really reach
 * these APIs" claim is backed by output from the app's own client and provider, not a mock.
 *
 * Credentials are read from the environment and never written anywhere:
 *   NVIDIA_API_KEY (or NVIDIA_NIM_API_KEY)
 *   ADZUNA_APP_ID, ADZUNA_APP_KEY
 * A key that is absent simply skips that half, so the probe never invents a success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage17LiveApiProbeTest {

    private val nvidiaKey: String
        get() = System.getenv("NVIDIA_API_KEY").orEmpty()
            .ifBlank { System.getenv("NVIDIA_NIM_API_KEY").orEmpty() }

    private val adzunaAppId: String get() = System.getenv("ADZUNA_APP_ID").orEmpty()
    private val adzunaAppKey: String get() = System.getenv("ADZUNA_APP_KEY").orEmpty()

    @Test
    fun probe() = runBlocking {
        assumeTrue("set LIVE_PROBE=1 to run the live API probe", System.getenv("LIVE_PROBE") == "1")

        println("==== STAGE 17 LIVE API PROBE ====")

        // ---- NVIDIA: list the models the account can actually use ----------
        if (nvidiaKey.isBlank()) {
            println("nvidia=skipped (set NVIDIA_API_KEY)")
        } else {
            val result = NvidiaAiClient().listModelIds(nvidiaKey)
            val ids = result.text.lines().filter { it.isNotBlank() }
            println("nvidia status=${result.status} models=${ids.size}")
            ids.take(25).forEach { println("  nvidia model=$it") }
            assertTrue("NVIDIA rejected the key: ${result.message}", result.isSuccess)
        }

        // ---- Adzuna: the version endpoint plus one real market search ------
        if (adzunaAppId.isBlank() || adzunaAppKey.isBlank()) {
            println("adzuna=skipped (set ADZUNA_APP_ID and ADZUNA_APP_KEY)")
        } else {
            val provider = AdzunaProvider(
                credentials = { AdzunaCredentials(adzunaAppId, adzunaAppKey) },
                feed = HttpJsonFeed,
                countries = listOf("gb"),
            )
            val check = provider.checkConnection()
            println("adzuna status=${check.status} market=${check.market} total=${check.totalResults}")
            println("adzuna sample=${check.sampleTitle}")
            println("adzuna message=${check.message}")
            assertTrue("Adzuna check failed: ${check.message}", check.isSuccess)
        }
    }
}
