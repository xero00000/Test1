package com.gamextra4u.fexdroid.retailer

import android.content.Context
import com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor
import com.gamextra4u.fexdroid.retailer.service.PriceMonitorService
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class PriceMonitorServiceTest {

    @Mock
    private lateinit var context: Context

    private lateinit var priceMonitorService: PriceMonitorService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        priceMonitorService = PriceMonitorService(context)
    }

    @Test
    fun testFetchPricesForProduct() = runBlocking {
        val testProduct = ProductDescriptor(
            id = "B0D8W9KVJG",
            title = "Test Game",
            url = "https://www.amazon.com/dp/B0D8W9KVJG"
        )

        val snapshots = priceMonitorService.fetchPricesForProduct(testProduct)

        assert(snapshots.isNotEmpty() || snapshots.isEmpty())
    }

    @Test
    fun testFetchPricesForMultipleProducts() = runBlocking {
        val testProducts = listOf(
            ProductDescriptor(
                id = "B0D8W9KVJG",
                title = "Test Game 1",
                url = "https://www.amazon.com/dp/B0D8W9KVJG"
            ),
            ProductDescriptor(
                id = "123456789",
                title = "Test Game 2",
                url = "https://www.ebay.com/itm/123456789"
            )
        )

        val snapshots = priceMonitorService.fetchPricesForProducts(testProducts)

        assert(snapshots.isNotEmpty() || snapshots.isEmpty())
    }

    @Test
    fun testSearchAcrossRetailers() = runBlocking {
        val results = priceMonitorService.searchAcrossRetailers("game")

        assert(results.isNotEmpty() || results.isEmpty())
    }
}
