package ru.savefood.app.feature.volunteer.data
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
class StartRouteRequestDtoTest {
    private val json = Json {
        explicitNulls = false
    }
    @Test
    fun noUserSelectedCapacityOmitsMaxStops() {
        val body = json.encodeToString(StartRouteRequestDto(lotId = 42))
        assertEquals("{\"lot_id\":42}", body)
    }
    @Test
    fun explicitUserSelectedCapacityIsSerialized() {
        val body = json.encodeToString(StartRouteRequestDto(lotId = 42, maxStops = 15))
        assertEquals("{\"lot_id\":42,\"max_stops\":15}", body)
    }
}
