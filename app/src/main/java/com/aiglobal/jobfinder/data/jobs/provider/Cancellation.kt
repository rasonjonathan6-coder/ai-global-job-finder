package com.aiglobal.jobfinder.data.jobs.provider

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but never swallows coroutine cancellation.
 *
 * The Kotlin standard-library [runCatching] captures every [Throwable], which includes
 * [CancellationException]. When a caller bounds a provider with `withTimeoutOrNull`, the
 * timeout cancels the provider's coroutine by throwing [CancellationException]; if a
 * provider caught that with a plain `runCatching` it would turn the cancellation into an
 * ordinary failure result, the caller's `awaitAll` would complete as if nothing happened,
 * and the in-flight network work would keep running past its deadline.
 *
 * This wrapper keeps the convenient [Result] shape for genuine failures (an `IOException`,
 * a malformed body) while rethrowing cancellation so it propagates to whoever cancelled
 * the coroutine. A [kotlinx.coroutines.TimeoutCancellationException] is a subtype of
 * [CancellationException] and is likewise rethrown, so a per-call timeout stays
 * distinguishable from an ordinary error.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
