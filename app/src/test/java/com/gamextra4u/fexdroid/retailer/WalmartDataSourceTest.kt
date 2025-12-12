package com.gamextra4u.fexdroid.retailer

import com.gamextra4u.fexdroid.retailer.data.walmart.WalmartDataSource
import com.gamextra4u.fexdroid.retailer.domain.Availability
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.network.RateLimiter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class WalmartDataSourceTest {

    @Mock
    private lateinit var httpClient: OkHttpClient

    private lateinit var rateLimiter: RateLimiter
    private lateinit var walmartDataSource: WalmartDataSource

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        rateLimiter = RateLimiter(10, 10)
        walmartDataSource = WalmartDataSource(httpClient, rateLimiter)
    }

    @Test
    fun testParseWalmartProduct() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "123456789",
            title = "Test Game",
            url = "https://www.walmart.com/ip/123456789"
        )

        val htmlFixture = loadHtmlFixture("walmart_product.html")

        val snapshot = walmartDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.retailer == "Walmart")
        assert(snapshot?.currencyCode == "USD")
        assert(snapshot?.availability in listOf(Availability.IN_STOCK, Availability.OUT_OF_STOCK))
    }

    @Test
    fun testWalmartInStock() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "123456789",
            title = "Test Game",
            url = "https://www.walmart.com/ip/123456789"
        )

        val htmlFixture = loadHtmlFixture("walmart_in_stock.html")

        val snapshot = walmartDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.availability == Availability.IN_STOCK)
    }

    private fun loadHtmlFixture(filename: String): String {
        val classLoader = this::class.java.classLoader!!
        return classLoader.getResourceAsStream("fixtures/$filename")?.bufferedReader()?.readText() ?: ""
    }
}
