package com.gamextra4u.fexdroid.retailer.data

import com.gamextra4u.fexdroid.retailer.domain.PriceSnapshot
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor

interface RetailerDataSource {
    suspend fun fetchPrice(product: ProductDescriptor): PriceSnapshot?
    suspend fun searchProducts(query: String): List<ProductDescriptor>
}
