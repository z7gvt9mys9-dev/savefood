package ru.savefood.app.feature.shop.lots

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.savefood.app.feature.shop.data.LotDto
import ru.savefood.app.feature.shop.data.LotUpdateDto

class LotUpdatePayloadTest {
    private val json = Json { explicitNulls = false }
    private val lot = LotDto(
        id = 7,
        shopId = 2,
        description = "Bread",
        quantity = 5.0,
        initialQuantity = 5.0,
        status = "active",
    )

    @Test
    fun unchangedQuantityIsOmittedFromProductionPayload() {
        val payload = buildLotUpdatePayload(
            lot, "Fresh bread", 5, "Выпечка", "Store", "", false,
        )
        val encoded = json.encodeToString(LotUpdateDto.serializer(), payload)

        assertEquals(null, payload.quantity)
        assertEquals(null, payload.expectedQuantity)
        assertEquals(null, payload.expectedInitialQuantity)
        assertFalse(encoded.contains("quantity"))
    }

    @Test
    fun explicitQuantityCarriesImmutableEditorBaselines() {
        val payload = buildLotUpdatePayload(
            lot, "Bread", 7, "Выпечка", "Store", "", false,
        )
        val encoded = json.encodeToString(LotUpdateDto.serializer(), payload)

        assertEquals(7, payload.quantity)
        assertEquals(5, payload.expectedQuantity)
        assertEquals(5, payload.expectedInitialQuantity)
        assertTrue(encoded.contains("\"quantity\":7"))
        assertTrue(encoded.contains("\"expected_quantity\":5"))
        assertTrue(encoded.contains("\"expected_initial_quantity\":5"))
    }
}
