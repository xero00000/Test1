package com.gamextra4u.fexdroid.retailer

import com.gamextra4u.fexdroid.retailer.network.retryWithExponentialBackoff
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RetryBackoffTest {

    @Test
    fun testSuccessOnFirstAttempt() = runBlocking {
        var attemptCount = 0
        val result = retryWithExponentialBackoff(maxRetries = 3) {
            attemptCount++
            "success"
        }

        assert(result == "success")
        assert(attemptCount == 1)
    }

    @Test
    fun testSuccessAfterRetries() = runBlocking {
        var attemptCount = 0
        val result = retryWithExponentialBackoff(maxRetries = 3) {
            attemptCount++
            if (attemptCount < 2) {
                throw IllegalStateException("Temporary failure")
            }
            "success"
        }

        assert(result == "success")
        assert(attemptCount == 2)
    }

    @Test(expected = Exception::class)
    fun testFailureAfterMaxRetries() = runBlocking {
        var attemptCount = 0
        retryWithExponentialBackoff(maxRetries = 3) {
            attemptCount++
            throw IllegalStateException("Persistent failure")
        }
    }

    @Test
    fun testExponentialBackoffTiming() = runBlocking {
        var attemptCount = 0
        val startTime = System.currentTimeMillis()

        try {
            retryWithExponentialBackoff(
                maxRetries = 3,
                initialDelayMillis = 100,
                maxDelayMillis = 1000
            ) {
                attemptCount++
                throw IllegalStateException("Test failure")
            }
        } catch (e: Exception) {
            // Expected
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        assert(attemptCount == 3)
        assert(elapsedTime >= 300)
    }
}
