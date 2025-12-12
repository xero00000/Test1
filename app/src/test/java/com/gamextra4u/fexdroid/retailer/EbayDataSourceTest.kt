package com.gamextra4u.fexdroid.retailer

import com.gamextra4u.fexdroid.retailer.data.ebay.EbayDataSource
import com.gamextra4u.fexdroid.retailer.domain.Availability
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.network.RateLimiter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class EbayDataSourceTest {

    @Mock
    private lateinit var httpClient: OkHttpClient

    private lateinit var rateLimiter: RateLimiter
    private lateinit var ebayDataSource: EbayDataSource

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        rateLimiter = RateLimiter(10, 10)
        ebayDataSource = EbayDataSource(httpClient, rateLimiter)
    }

    @Test
    fun testParseEbayProduct() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "123456789",
            title = "Test Game",
            url = "https://www.ebay.com/itm/123456789"
        )

        val htmlFixture = loadHtmlFixture("ebay_product.html")

        val snapshot = ebayDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.retailer == "eBay")
        assert(snapshot?.currencyCode == "USD")
        assert(snapshot?.availability in listOf(Availability.IN_STOCK, Availability.OUT_OF_STOCK))
    }

    @Test
    fun testEbayInStock() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "123456789",
            title = "Test Game",
            url = "https://www.ebay.com/itm/123456789"
        )

        val htmlFixture = loadHtmlFixture("ebay_in_stock.html")

        val snapshot = ebayDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.availability == Availability.IN_STOCK)
    }

    private fun loadHtmlFixture(filename: String): String {
        val classLoader = this::class.java.classLoader!!
        return classLoader.getResourceAsStream("fixtures/$filename")?.bufferedReader()?.readText() ?: ""
    }
}
