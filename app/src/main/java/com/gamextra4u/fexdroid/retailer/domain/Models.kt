package com.gamextra4u.fexdroid.retailer.domain

import kotlinx.serialization.Serializable
import java.util.Currency

@Serializable
data class ProductDescriptor(
    val id: String,
    val title: String,
    val url: String
)

@Serializable
data class PriceSnapshot(
    val productId: String,
    val retailer: String,
    val price: Double,
    val currencyCode: String,
    val availability: Availability,
    val shippingCost: Double?,
    val discount: Int?,
    val timestamp: Long,
    val originalPrice: Double? = null
)

enum class Availability {
    IN_STOCK,
    OUT_OF_STOCK,
    PREORDER,
    UNKNOWN
}

@Serializable
data class DealSignal(
    val productId: String,
    val title: String,
    val retailer: String,
    val currentPrice: Double,
    val previousPrice: Double?,
    val discount: Int,
    val url: String,
    val timestamp: Long,
    val signalType: DealSignalType
)

enum class DealSignalType {
    PRICE_DROP,
    BACK_IN_STOCK,
    FLASH_SALE
}
