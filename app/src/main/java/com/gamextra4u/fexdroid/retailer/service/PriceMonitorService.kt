package com.gamextra4u.fexdroid.retailer.service

import android.content.Context
import android.util.Log
import com.gamextra4u.fexdroid.retailer.data.RetailerDataSource
import com.gamextra4u.fexdroid.retailer.data.amazon.AmazonDataSource
import com.gamextra4u.fexdroid.retailer.data.bestbuy.BestBuyDataSource
import com.gamextra4u.fexdroid.retailer.data.ebay.EbayDataSource
import com.gamextra4u.fexdroid.retailer.data.newegg.NeweggDataSource
import com.gamextra4u.fexdroid.retailer.data.walmart.WalmartDataSource
import com.gamextra4u.fexdroid.retailer.domain.PriceSnapshot
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.domain.Retailer
import com.gamextra4u.fexdroid.retailer.network.RetailerClientPool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class PriceMonitorService(context: Context) {
    private val clientPool = RetailerClientPool()
    private val dataSources: Map<Retailer, RetailerDataSource> = initializeDataSources()

    private fun initializeDataSources(): Map<Retailer, RetailerDataSource> {
        return mapOf(
            Retailer.AMAZON to AmazonDataSource(
                clientPool.getClient("amazon"),
                clientPool.getRateLimiter("amazon")
            ),
            Retailer.EBAY to EbayDataSource(
                clientPool.getClient("ebay"),
                clientPool.getRateLimiter("ebay")
            ),
            Retailer.BEST_BUY to BestBuyDataSource(
                clientPool.getClient("bestbuy"),
                clientPool.getRateLimiter("bestbuy")
            ),
            Retailer.NEWEGG to NeweggDataSource(
                clientPool.getClient("newegg"),
                clientPool.getRateLimiter("newegg")
            ),
            Retailer.WALMART to WalmartDataSource(
                clientPool.getClient("walmart"),
                clientPool.getRateLimiter("walmart")
            )
        )
    }

    suspend fun fetchPricesForProduct(product: ProductDescriptor): List<PriceSnapshot> {
        return coroutineScope {
            val tasks = dataSources.map { (_, dataSource) ->
                async {
                    try {
                        dataSource.fetchPrice(product)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching price from retailer", e)
                        null
                    }
                }
            }
            tasks.awaitAll().filterNotNull()
        }
    }

    suspend fun fetchPricesForProducts(products: List<ProductDescriptor>): List<PriceSnapshot> {
        return coroutineScope {
            val tasks = products.flatMap { product ->
                dataSources.map { (_, dataSource) ->
                    async {
                        try {
                            dataSource.fetchPrice(product)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching price from retailer", e)
                            null
                        }
                    }
                }
            }
            tasks.awaitAll().filterNotNull()
        }
    }

    suspend fun searchAcrossRetailers(query: String): List<Pair<Retailer, ProductDescriptor>> {
        return coroutineScope {
            val tasks = dataSources.map { (retailer, dataSource) ->
                async {
                    try {
                        val products = dataSource.searchProducts(query)
                        products.map { retailer to it }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error searching retailer for query: $query", e)
                        emptyList()
                    }
                }
            }
            tasks.awaitAll().flatten()
        }
    }

    companion object {
        private const val TAG = "PriceMonitorService"
    }
}
