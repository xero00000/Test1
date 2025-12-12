package com.gamextra4u.fexdroid.retailer.data.bestbuy

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

class BestBuyDataSource(
    private val httpClient: OkHttpClient,
    private val rateLimiter: RateLimiter
) : RetailerDataSource {

    override suspend fun fetchPrice(product: ProductDescriptor): PriceSnapshot? {
        return retryWithExponentialBackoff {
            rateLimiter.acquire()
            fetchBestBuyPrice(product)
        }
    }

    override suspend fun searchProducts(query: String): List<ProductDescriptor> {
        return try {
            retryWithExponentialBackoff {
                rateLimiter.acquire()
                searchBestBuyProducts(query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search Best Buy products", e)
            emptyList()
        }
    }

    private suspend fun fetchBestBuyPrice(product: ProductDescriptor): PriceSnapshot? {
        return try {
            val sku = extractSKU(product.id)
            val url = "https://www.bestbuy.com/site/$sku.p"

            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Best Buy request failed with code ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            parseBestBuyPage(body, sku, product)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Best Buy price", e)
            null
        }
    }

    private suspend fun searchBestBuyProducts(query: String): List<ProductDescriptor> {
        return emptyList()
    }

    private fun parseBestBuyPage(html: String, sku: String, product: ProductDescriptor): PriceSnapshot? {
        return try {
            val pricePattern = """"price":([\d.]+)""".toRegex()
            val priceMatch = pricePattern.find(html)

            if (priceMatch != null) {
                val price = priceMatch.groupValues[1].toDoubleOrNull() ?: return null
                val availability = if (html.contains("\"addToCart\"") || html.contains("\"inStock\":true")) {
                    Availability.IN_STOCK
                } else {
                    Availability.OUT_OF_STOCK
                }

                PriceSnapshot(
                    productId = sku,
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
            Log.e(TAG, "Error parsing Best Buy page", e)
            null
        }
    }

    private fun extractSKU(productId: String): String {
        return productId
    }

    companion object {
        private const val TAG = "BestBuyDataSource"
        private const val RETAILER_NAME = "Best Buy"
    }
}

@Serializable
data class BestBuyProduct(
    val sku: Long? = null,
    val name: String? = null,
    val priceWithoutContract: Double? = null,
    val onSale: Boolean? = null,
    val inStockOnline: Boolean? = null
)

@Serializable
data class BestBuyResponse(
    val products: List<BestBuyProduct>? = null,
    val total: Int? = null
)
