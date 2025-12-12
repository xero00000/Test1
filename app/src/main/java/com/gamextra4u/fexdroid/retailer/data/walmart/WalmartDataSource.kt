package com.gamextra4u.fexdroid.retailer.data.walmart

import android.util.Log
import com.gamextra4u.fexdroid.retailer.data.RetailerDataSource
import com.gamextra4u.fexdroid.retailer.domain.Availability
import com.gamextra4u.fexdroid.retailer.domain.PriceSnapshot
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.network.RateLimiter
import com.gamextra4u.fexdroid.retailer.network.retryWithExponentialBackoff
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

class WalmartDataSource(
    private val httpClient: OkHttpClient,
    private val rateLimiter: RateLimiter
) : RetailerDataSource {

    override suspend fun fetchPrice(product: ProductDescriptor): PriceSnapshot? {
        return retryWithExponentialBackoff {
            rateLimiter.acquire()
            fetchWalmartPrice(product)
        }
    }

    override suspend fun searchProducts(query: String): List<ProductDescriptor> {
        return try {
            retryWithExponentialBackoff {
                rateLimiter.acquire()
                searchWalmartProducts(query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search Walmart products", e)
            emptyList()
        }
    }

    private suspend fun fetchWalmartPrice(product: ProductDescriptor): PriceSnapshot? {
        return try {
            val usItemId = extractUsItemId(product.id)
            val url = "https://www.walmart.com/ip/$usItemId"

            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Walmart request failed with code ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            parseWalmartPage(body, usItemId, product)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Walmart price", e)
            null
        }
    }

    private suspend fun searchWalmartProducts(query: String): List<ProductDescriptor> {
        return emptyList()
    }

    private fun parseWalmartPage(html: String, usItemId: String, product: ProductDescriptor): PriceSnapshot? {
        return try {
            val pricePattern = """"currentPrice":([\d.]+)""".toRegex()
            val priceMatch = pricePattern.find(html)

            if (priceMatch != null) {
                val price = priceMatch.groupValues[1].toDoubleOrNull() ?: return null
                val availability = if (html.contains("Add to cart") || html.contains("\"inStockIndicator\":true")) {
                    Availability.IN_STOCK
                } else {
                    Availability.OUT_OF_STOCK
                }

                PriceSnapshot(
                    productId = usItemId,
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
            Log.e(TAG, "Error parsing Walmart page", e)
            null
        }
    }

    private fun extractUsItemId(productId: String): String {
        return productId
    }

    companion object {
        private const val TAG = "WalmartDataSource"
        private const val RETAILER_NAME = "Walmart"
    }
}

@Serializable
data class WalmartProduct(
    val usItemId: String? = null,
    val name: String? = null,
    val salePrice: Double? = null,
    val regularPrice: Double? = null,
    val available: Boolean? = null
)

@Serializable
data class WalmartResponse(
    val items: List<WalmartProduct>? = null
)
