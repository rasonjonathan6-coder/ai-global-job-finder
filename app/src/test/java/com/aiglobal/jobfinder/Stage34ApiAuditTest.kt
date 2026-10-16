package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.HttpJsonClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 34 — secret redaction in request URLs.
 *
 * An Adzuna key travels as a query parameter and a Jooble key travels as the final path
 * segment (`https://jooble.org/api/<key>`). [HttpJsonClient.redactUrl] is the single place
 * that removes a key from a URL before it can reach a message, a log or a stack trace, so
 * both shapes must be redacted and neither must start redacting ordinary URLs.
 *
 * These are plain JVM tests: no network and no credential is involved.
 */
class Stage34ApiAuditTest {

    @Test
    fun aJoobleKeyInThePathIsRedacted() {
        val key = "8f3c1d29ab6e4f70b2d5c8a90e1f3b7c"
        val url = "https://jooble.org/api/$key"
        val redacted = HttpJsonClient.redactUrl(url)
        assertFalse("the key must not survive", redacted.contains(key))
        assertTrue("the host stays so the source is still identifiable", redacted.contains("https://jooble.org/api/"))
        assertEquals("https://jooble.org/api/***", redacted)
    }

    @Test
    fun aJoobleKeyInThePathIsRedactedEvenWithATrailingQuery() {
        val key = "8f3c1d29ab6e4f70b2d5c8a90e1f3b7c"
        val url = "https://jooble.org/api/$key?page=1"
        val redacted = HttpJsonClient.redactUrl(url)
        assertFalse(redacted.contains(key))
        assertEquals("https://jooble.org/api/***?page=1", redacted)
    }

    @Test
    fun anAdzunaQueryKeyIsStillRedactedAlongsideAPath() {
        val url = "https://api.adzuna.com/v1/api/jobs/gb/search/1" +
            "?app_id=abc123&app_key=supersecret&results_per_page=5"
        val redacted = HttpJsonClient.redactUrl(url)
        assertFalse(redacted.contains("supersecret"))
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("app_key=***"))
        assertTrue(redacted.contains("results_per_page=5"))
    }

    @Test
    fun shortPathSegmentsAreNotMistakenForASecret() {
        // Adzuna's own path: market, "search" and a page number are all short and must stay.
        assertEquals(
            "https://api.adzuna.com/v1/api/jobs/gb/search/1",
            HttpJsonClient.redactUrl("https://api.adzuna.com/v1/api/jobs/gb/search/1"),
        )
        assertEquals(
            "https://boards-api.greenhouse.io/v1/boards/redwoodmaterials/jobs",
            HttpJsonClient.redactUrl("https://boards-api.greenhouse.io/v1/boards/redwoodmaterials/jobs"),
        )
    }

    @Test
    fun hyphenatedPathIdsAreNotRedacted() {
        // Lever postings and Workday externalPaths are hyphenated ids, not keys.
        val lever = "https://api.lever.co/v0/postings/sila/da9fd178-def5-490f-8005-20933f99cf29"
        assertEquals(lever, HttpJsonClient.redactUrl(lever))
        val workday = "https://riotinto.wd3.myworkdayjobs.com/wday/cxs/riotinto/RioTinto_Careers" +
            "/job/Kitimat/Heavy-Duty-Equipment-Technicians--HDET-_R59061"
        assertEquals(workday, HttpJsonClient.redactUrl(workday))
    }

    @Test
    fun aPlainUrlIsUntouched() {
        val url = "https://remotive.com/api/remote-jobs"
        assertEquals(url, HttpJsonClient.redactUrl(url))
    }
}
