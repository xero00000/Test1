package com.gamextra4u.fexdroid.retailer.data.amazon

import android.util.Log
import com.gamextra4u.fexdroid.retailer.data.RetailerDataSource
import com.gamextra4u.fexdroid.retailer.domain.Availability
import com.gamextra4u.fexdroid.retailer.domain.PriceSnapshot
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.network.RateLimiter
import com.gamextra4u.fexdroid.retailer.network.retryWithExponentialBackoff
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class AmazonDataSource(
    private val httpClient: OkHttpClient,
    private val rateLimiter: RateLimiter
) : RetailerDataSource {

    override suspend fun fetchPrice(product: ProductDescriptor): PriceSnapshot? {
        return retryWithExponentialBackoff {
            rateLimiter.acquire()
            fetchAmazonPrice(product)
        }
    }

    override suspend fun searchProducts(query: String): List<ProductDescriptor> {
        return try {
            retryWithExponentialBackoff {
                rateLimiter.acquire()
                searchAmazonProducts(query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search Amazon products", e)
            emptyList()
        }
    }

    private suspend fun fetchAmazonPrice(product: ProductDescriptor): PriceSnapshot? {
        return try {
            val asin = extractASIN(product.id)
            val url = "https://www.amazon.com/dp/$asin"

            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Amazon request failed with code ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            parseAmazonPage(body, asin, product)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Amazon price", e)
            null
        }
    }

    private suspend fun searchAmazonProducts(query: String): List<ProductDescriptor> {
        return emptyList()
    }

    private fun parseAmazonPage(html: String, asin: String, product: ProductDescriptor): PriceSnapshot? {
        return try {
            val pricePattern = """price":"([\d.]+)""".toRegex()
            val priceMatch = pricePattern.find(html)

            if (priceMatch != null) {
                val price = priceMatch.groupValues[1].toDoubleOrNull() ?: return null
                val availability = if (html.contains("Add to Cart")) {
                    Availability.IN_STOCK
                } else {
                    Availability.OUT_OF_STOCK
                }

                PriceSnapshot(
                    productId = asin,
                    retailer = RETAILER_NAME,
                    price = price,
                    currencyCode = "USD",
                    availability = availability,
                    shippingCost = null,
                    discount = null,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Amazon page", e)
            null
        }
    }

    private fun extractASIN(productId: String): String {
        return productId.takeIf { it.length == 10 } ?: productId
    }

    companion object {
        private const val TAG = "AmazonDataSource"
        private const val RETAILER_NAME = "Amazon"
    }
}

@Serializable
data class AmazonProductResponse(
    val Items: List<AmazonItem>? = null
)

@Serializable
data class AmazonItem(
    val ASIN: String? = null,
    val ItemInfo: AmazonItemInfo? = null,
    val Offers: AmazonOffers? = null
)

@Serializable
data class AmazonItemInfo(
    val Title: AmazonTitle? = null
)

@Serializable
data class AmazonTitle(
    val DisplayValue: String? = null
)

@Serializable
data class AmazonOffers(
    val Summaries: List<AmazonOfferSummary>? = null
)

@Serializable
data class AmazonOfferSummary(
    val Price: String? = null,
    val Condition: String? = null
)
