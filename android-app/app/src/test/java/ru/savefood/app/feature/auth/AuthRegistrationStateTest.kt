package ru.savefood.app.feature.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRegistrationStateTest {
    @Test
    fun shopRequiresSeparateContactAndConsent() {
        val base = AuthUiState(
            mode = AuthMode.REGISTER,
            role = AuthRole.SHOP,
            name = "Bakery",
            username = "shop@example.com",
            password = "password1",
            confirmPassword = "password1",
        )

        assertFalse(base.registrationValid)
        assertFalse(base.copy(contact = "+7 700 000 00 00").registrationValid)
        assertTrue(base.copy(contact = "+7 700 000 00 00", agreed = true).registrationValid)
    }

    @Test
    fun recipientUsesPhoneAsContactAndRejectsPasswordMismatch() {
        val base = AuthUiState(
            mode = AuthMode.REGISTER,
            role = AuthRole.NEEDY,
            name = "Anna",
            username = "+7 700 000 00 00",
            password = "password1",
            confirmPassword = "password2",
            agreed = true,
        )

        assertFalse(base.registrationValid)
        assertTrue(base.copy(confirmPassword = "password1").registrationValid)
    }
}
