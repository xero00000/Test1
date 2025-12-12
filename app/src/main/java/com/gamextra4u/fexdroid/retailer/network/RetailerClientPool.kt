package com.gamextra4u.fexdroid.retailer.network

import android.util.Log
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

class RetailerClientPool {
    private val clients = mutableMapOf<String, OkHttpClient>()
    private val rateLimiters = mutableMapOf<String, RateLimiter>()

    fun getClient(key: String = DEFAULT_CLIENT_KEY): OkHttpClient {
        return clients.getOrPut(key) {
            OkHttpClient.Builder()
                .addInterceptor(UserAgentInterceptor())
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }

    fun getRateLimiter(retailerKey: String): RateLimiter {
        return rateLimiters.getOrPut(retailerKey) {
            RateLimiter(REQUESTS_PER_SECOND, REQUESTS_PER_SECOND)
        }
    }

    companion object {
        private const val TAG = "RetailerClientPool"
        private const val DEFAULT_CLIENT_KEY = "default"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val REQUESTS_PER_SECOND = 1
    }
}

class RateLimiter(private val requestsPerSecond: Int, private val burst: Int) {
    private var lastTimestamp = System.currentTimeMillis()
    private var tokens = burst.toDouble()
    private val lock = Any()

    suspend fun acquire() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastTimestamp) / 1000.0
            lastTimestamp = now

            tokens = min(burst.toDouble(), tokens + elapsed * requestsPerSecond)

            if (tokens < 1) {
                val waitMillis = ((1 - tokens) / requestsPerSecond * 1000).toLong()
                Log.d("RateLimiter", "Rate limiting, waiting ${waitMillis}ms")
                Thread.sleep(waitMillis)
                tokens = 1.0
            }

            tokens -= 1
        }
    }
}

suspend fun <T> retryWithExponentialBackoff(
    maxRetries: Int = DEFAULT_MAX_RETRIES,
    initialDelayMillis: Long = INITIAL_DELAY_MILLIS,
    maxDelayMillis: Long = MAX_DELAY_MILLIS,
    backoffMultiplier: Double = BACKOFF_MULTIPLIER,
    block: suspend () -> T
): T {
    var lastException: Exception? = null

    for (attempt in 0 until maxRetries) {
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            if (attempt < maxRetries - 1) {
                val delayMillis = (initialDelayMillis * backoffMultiplier.pow(attempt.toDouble()))
                    .toLong()
                    .coerceAtMost(maxDelayMillis)
                Log.d("RetryBackoff", "Attempt ${attempt + 1} failed, retrying in ${delayMillis}ms", e)
                kotlinx.coroutines.delay(delayMillis)
            }
        }
    }

    throw lastException ?: Exception("All retry attempts failed")
}

private const val DEFAULT_MAX_RETRIES = 3
private const val INITIAL_DELAY_MILLIS = 500L
private const val MAX_DELAY_MILLIS = 30000L
private const val BACKOFF_MULTIPLIER = 2.0
