package ru.savefood.app.feature.volunteer.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VolunteerStatsDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deserializesCurrentBackendStatsResponseWithoutLosingLevelOrStatsFields() {
        val stats = json.decodeFromString<StatsDto>(
            """
            {
              "total_routes": 8,
              "total_deliveries": 5,
              "total_kg": 25.5,
              "avg_rating": 4.8,
              "rating_count": 4,
              "achievements": ["first_delivery"],
              "level": {
                "code": "helper",
                "points": 75.5,
                "next_code": "courier",
                "points_to_next": 124.5,
                "progress": 0.17
              }
            }
            """.trimIndent(),
        )

        assertEquals(8, stats.totalRoutes)
        assertEquals(5, stats.totalDeliveries)
        assertEquals(25.5, stats.totalKg, 0.0)
        assertEquals(4.8, stats.avgRating!!, 0.0)
        assertEquals(4, stats.ratingCount)
        assertEquals(listOf("first_delivery"), stats.achievements)
        assertEquals("helper", stats.level.code)
        assertEquals(75.5, stats.level.points, 0.0)
        assertEquals("courier", stats.level.nextCode)
        assertEquals(124.5, stats.level.pointsToNext, 0.0)
        assertEquals(0.17, stats.level.progress, 0.0)
    }

    @Test
    fun deserializesTerminalLevelAndUnrelatedVolunteerResponseDtos() {
        val stats = json.decodeFromString<StatsDto>(
            """{"total_routes":1,"total_deliveries":1000,"total_kg":1000.0,"avg_rating":null,"rating_count":0,"achievements":[],"level":{"code":"city_hero","points":11000.0,"next_code":null,"points_to_next":0.0,"progress":1.0}}""",
        )
        val thanks = json.decodeFromString<ThanksDto>(
            """{"rating":5,"comment":"Спасибо","category":"produce","created_at":"2026-08-28T12:00:00Z"}""",
        )
        val team = json.decodeFromString<TeamEnvelopeDto>(
            """{"team":{"id":7,"name":"Rescuers","join_code":"ABC123","members":3,"deliveries":12,"kg":42.5}}""",
        )

        assertEquals("city_hero", stats.level.code)
        assertNull(stats.level.nextCode)
        assertEquals(1.0, stats.level.progress, 0.0)
        assertEquals("Спасибо", thanks.comment)
        assertEquals("Rescuers", team.team!!.name)
    }

    @Test
    fun rejectsLegacyScalarLevelResponse() {
        val incompatible =
            """{"total_routes":0,"total_deliveries":0,"total_kg":0.0,"avg_rating":null,"rating_count":0,"achievements":[],"level":0}"""

        assertThrows(SerializationException::class.java) {
            json.decodeFromString<StatsDto>(incompatible)
        }
    }
}
