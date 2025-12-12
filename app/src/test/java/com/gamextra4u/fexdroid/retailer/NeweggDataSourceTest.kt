package com.gamextra4u.fexdroid.retailer

import com.gamextra4u.fexdroid.retailer.data.newegg.NeweggDataSource
import com.gamextra4u.fexdroid.retailer.domain.Availability
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.network.RateLimiter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class NeweggDataSourceTest {

    @Mock
    private lateinit var httpClient: OkHttpClient

    private lateinit var rateLimiter: RateLimiter
    private lateinit var neweggDataSource: NeweggDataSource

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        rateLimiter = RateLimiter(10, 10)
        neweggDataSource = NeweggDataSource(httpClient, rateLimiter)
    }

    @Test
    fun testParseNeweggProduct() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "N82E16874105000",
            title = "Test Game",
            url = "https://www.newegg.com/p/N82E16874105000"
        )

        val htmlFixture = loadHtmlFixture("newegg_product.html")

        val snapshot = neweggDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.retailer == "Newegg")
        assert(snapshot?.currencyCode == "USD")
        assert(snapshot?.availability in listOf(Availability.IN_STOCK, Availability.OUT_OF_STOCK))
    }

    @Test
    fun testNeweggInStock() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "N82E16874105000",
            title = "Test Game",
            url = "https://www.newegg.com/p/N82E16874105000"
        )

        val htmlFixture = loadHtmlFixture("newegg_in_stock.html")

        val snapshot = neweggDataSource.fetchPrice(testProduct)

        assert(snapshot != null)
        assert(snapshot?.availability == Availability.IN_STOCK)
    }

    private fun loadHtmlFixture(filename: String): String {
        val classLoader = this::class.java.classLoader!!
        return classLoader.getResourceAsStream("fixtures/$filename")?.bufferedReader()?.readText() ?: ""
    }
}
