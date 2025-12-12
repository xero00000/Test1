package com.gamextra4u.fexdroid.retailer.data.ebay

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

class EbayDataSource(
    private val httpClient: OkHttpClient,
    private val rateLimiter: RateLimiter
) : RetailerDataSource {

    override suspend fun fetchPrice(product: ProductDescriptor): PriceSnapshot? {
        return retryWithExponentialBackoff {
            rateLimiter.acquire()
            fetchEbayPrice(product)
        }
    }

    override suspend fun searchProducts(query: String): List<ProductDescriptor> {
        return try {
            retryWithExponentialBackoff {
                rateLimiter.acquire()
                searchEbayProducts(query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search eBay products", e)
            emptyList()
        }
    }

    private suspend fun fetchEbayPrice(product: ProductDescriptor): PriceSnapshot? {
        return try {
            val itemId = extractItemId(product.id)
            val url = "https://www.ebay.com/itm/$itemId"

            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "eBay request failed with code ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            parseEbayPage(body, itemId, product)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching eBay price", e)
            null
        }
    }

    private suspend fun searchEbayProducts(query: String): List<ProductDescriptor> {
        return emptyList()
    }

    private fun parseEbayPage(html: String, itemId: String, product: ProductDescriptor): PriceSnapshot? {
        return try {
            val pricePattern = """"converted_price":"([\d.]+)""".toRegex()
            val priceMatch = pricePattern.find(html)

            if (priceMatch != null) {
                val price = priceMatch.groupValues[1].toDoubleOrNull() ?: return null
                val availability = if (html.contains("Add to cart") || html.contains("\"conditionId\":\"3000\"")) {
                    Availability.IN_STOCK
                } else {
                    Availability.OUT_OF_STOCK
                }

                PriceSnapshot(
                    productId = itemId,
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
            Log.e(TAG, "Error parsing eBay page", e)
            null
        }
    }

    private fun extractItemId(productId: String): String {
        return productId
    }

    companion object {
        private const val TAG = "EbayDataSource"
        private const val RETAILER_NAME = "eBay"
    }
}

@Serializable
data class EbaySearchResponse(
    val findItemsAdvancedResponse: List<FindItemsResponse>? = null
)

@Serializable
data class FindItemsResponse(
    val searchStatus: List<SearchStatus>? = null,
    val searchResult: List<SearchResult>? = null
)

@Serializable
data class SearchStatus(
    val timestamp: String? = null
)

@Serializable
data class SearchResult(
    val item: List<EbayItem>? = null
)

@Serializable
data class EbayItem(
    val itemId: List<String>? = null,
    val title: List<String>? = null,
    val sellingStatus: List<SellingStatus>? = null
)

@Serializable
data class SellingStatus(
    val currentPrice: List<Price>? = null,
    val convertedCurrentPrice: List<Price>? = null
)

@Serializable
data class Price(
    val __value__: String? = null,
    val currencyId: String? = null
)
