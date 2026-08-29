package ru.savefood.app.feature.volunteer.data

import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.Query

class VolunteerMapRequestContractTest {

    @Test
    fun mapRequestRequiresCityQueryParameter() {
        val method = VolunteerApi::class.java.methods.single { it.name == "getMap" }
        val query = method.parameterAnnotations.flatMap { it.asIterable() }.filterIsInstance<Query>().single()

        assertEquals("city", query.value)
    }
}
