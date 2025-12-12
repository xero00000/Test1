package com.gamextra4u.fexdroid.retailer.data.newegg

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

class NeweggDataSource(
    private val httpClient: OkHttpClient,
    private val rateLimiter: RateLimiter
) : RetailerDataSource {

    override suspend fun fetchPrice(product: ProductDescriptor): PriceSnapshot? {
        return retryWithExponentialBackoff {
            rateLimiter.acquire()
            fetchNeweggPrice(product)
        }
    }

    override suspend fun searchProducts(query: String): List<ProductDescriptor> {
        return try {
            retryWithExponentialBackoff {
                rateLimiter.acquire()
                searchNeweggProducts(query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search Newegg products", e)
            emptyList()
        }
    }

    private suspend fun fetchNeweggPrice(product: ProductDescriptor): PriceSnapshot? {
        return try {
            val itemNumber = extractItemNumber(product.id)
            val url = "https://www.newegg.com/p/$itemNumber"

            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Newegg request failed with code ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            parseNeweggPage(body, itemNumber, product)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Newegg price", e)
            null
        }
    }

    private suspend fun searchNeweggProducts(query: String): List<ProductDescriptor> {
        return emptyList()
    }

    private fun parseNeweggPage(html: String, itemNumber: String, product: ProductDescriptor): PriceSnapshot? {
        return try {
            val pricePattern = """"price":"([\d.]+)""".toRegex()
            val priceMatch = pricePattern.find(html)

            if (priceMatch != null) {
                val price = priceMatch.groupValues[1].toDoubleOrNull() ?: return null
                val availability = if (html.contains("Add to cart") || html.contains("In Stock")) {
                    Availability.IN_STOCK
                } else {
                    Availability.OUT_OF_STOCK
                }

                PriceSnapshot(
                    productId = itemNumber,
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
            Log.e(TAG, "Error parsing Newegg page", e)
            null
        }
    }

    private fun extractItemNumber(productId: String): String {
        return productId
    }

    companion object {
        private const val TAG = "NeweggDataSource"
        private const val RETAILER_NAME = "Newegg"
    }
}

@Serializable
data class NeweggProduct(
    val ItemNumber: String? = null,
    val Title: String? = null,
    val FinalPrice: Double? = null,
    val OriginPrice: Double? = null,
    val Instock: Boolean? = null
)

@Serializable
data class NeweggResponse(
    val ProductList: List<NeweggProduct>? = null
)
