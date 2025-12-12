package com.gamextra4u.fexdroid.retailer

import com.gamextra4u.fexdroid.retailer.data.amazon.AmazonDataSource
import com.gamextra4u.fexdroid.retailer.domain.Availability
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.network.RateLimiter
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class AmazonDataSourceTest {

    @Mock
    private lateinit var httpClient: OkHttpClient

    private lateinit var rateLimiter: RateLimiter
    private lateinit var amazonDataSource: AmazonDataSource

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        rateLimiter = RateLimiter(10, 10)
        amazonDataSource = AmazonDataSource(httpClient, rateLimiter)
    }

    @Test
    fun testParseAmazonProduct() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "B0D8W9KVJG",
            title = "Test Game",
            url = "https://www.amazon.com/dp/B0D8W9KVJG"
        )

        val htmlFixture = loadHtmlFixture("amazon_product.html")

        val snapshot = amazonDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.retailer == "Amazon")
        assert(snapshot?.currencyCode == "USD")
        assert(snapshot?.availability in listOf(Availability.IN_STOCK, Availability.OUT_OF_STOCK))
    }

    @Test
    fun testAmazonOutOfStock() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "B0D8W9KVJG",
            title = "Test Game",
            url = "https://www.amazon.com/dp/B0D8W9KVJG"
        )

        val htmlFixture = loadHtmlFixture("amazon_out_of_stock.html")

        val snapshot = amazonDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.availability == Availability.OUT_OF_STOCK)
    }

    private fun loadHtmlFixture(filename: String): String {
        val classLoader = this::class.java.classLoader!!
        return classLoader.getResourceAsStream("fixtures/$filename")?.bufferedReader()?.readText() ?: ""
    }
}
