package com.gamextra4u.fexdroid.retailer

import com.gamextra4u.fexdroid.retailer.data.bestbuy.BestBuyDataSource
import com.gamextra4u.fexdroid.retailer.domain.Availability
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.network.RateLimiter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class BestBuyDataSourceTest {

    @Mock
    private lateinit var httpClient: OkHttpClient

    private lateinit var rateLimiter: RateLimiter
    private lateinit var bestBuyDataSource: BestBuyDataSource

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        rateLimiter = RateLimiter(10, 10)
        bestBuyDataSource = BestBuyDataSource(httpClient, rateLimiter)
    }

    @Test
    fun testParseBestBuyProduct() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "6450543",
            title = "Test Game",
            url = "https://www.bestbuy.com/site/6450543.p"
        )

        val htmlFixture = loadHtmlFixture("bestbuy_product.html")

        val snapshot = bestBuyDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.retailer == "Best Buy")
        assert(snapshot?.currencyCode == "USD")
        assert(snapshot?.availability in listOf(Availability.IN_STOCK, Availability.OUT_OF_STOCK))
    }

    @Test
    fun testBestBuyInStock() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "6450543",
            title = "Test Game",
            url = "https://www.bestbuy.com/site/6450543.p"
        )

        val htmlFixture = loadHtmlFixture("bestbuy_in_stock.html")

        val snapshot = bestBuyDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.availability == Availability.IN_STOCK)
    }

    private fun loadHtmlFixture(filename: String): String {
        val classLoader = this::class.java.classLoader!!
        return classLoader.getResourceAsStream("fixtures/$filename")?.bufferedReader()?.readText() ?: ""
    }
}
