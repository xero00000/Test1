package com.gamextra4u.fexdroid.monitoring

interface PriceRepositoryInterface {
    suspend fun savePrices(prices: List<PriceUpdate>)
    suspend fun getPriceHistory(gameId: String): List<PriceUpdate>
    suspend fun getWatchedGames(): List<String>
}
