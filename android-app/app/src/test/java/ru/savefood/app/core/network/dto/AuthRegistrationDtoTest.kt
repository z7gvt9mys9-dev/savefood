package ru.savefood.app.core.network.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthRegistrationDtoTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun shopPayloadMatchesPublicRegistrationContract() {
        val body = json.encodeToString(
            ShopRegistrationRequest(
                name = "Bakery",
                contact = "+7 700 000 00 00",
                username = "shop@example.com",
                password = "password1",
                kind = "private",
            ),
        )

        assertEquals(
            "{\"name\":\"Bakery\",\"contact\":\"+7 700 000 00 00\",\"username\":\"shop@example.com\",\"password\":\"password1\",\"kind\":\"private\"}",
            body,
        )
        assertFalse(body.contains("lat"))
        assertFalse(body.contains("lon"))
    }
}
