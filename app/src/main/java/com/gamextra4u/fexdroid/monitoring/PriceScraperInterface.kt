package com.gamextra4u.fexdroid.monitoring

interface PriceScraperInterface {
    suspend fun scrapePrices(): List<PriceUpdate>
}

data class PriceUpdate(
    val gameId: String,
    val gameName: String,
    val currentPrice: Double,
    val previousPrice: Double?,
    val discount: Int?,
    val timestamp: Long
)
