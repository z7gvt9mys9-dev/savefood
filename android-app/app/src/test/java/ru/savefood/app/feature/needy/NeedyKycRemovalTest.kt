package ru.savefood.app.feature.needy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Test

class NeedyKycRemovalTest {

    @Test
    fun needySourcesContainNoKycUploadOrGate() {
        val main = sequenceOf(Path.of("src/main"), Path.of("app/src/main"))
            .first(Files::isDirectory)
        val files = listOf(
            "java/ru/savefood/app/feature/needy/data/NeedyApi.kt",
            "java/ru/savefood/app/feature/needy/data/NeedyDtos.kt",
            "java/ru/savefood/app/feature/needy/data/NeedyRepository.kt",
            "java/ru/savefood/app/feature/needy/profile/NeedyProfileViewModel.kt",
            "java/ru/savefood/app/feature/needy/profile/NeedyProfileScreen.kt",
            "res/values/strings_needy.xml",
            "res/values-en/strings_needy.xml",
        )
        val source = files.joinToString("\n") { main.resolve(it).readText() }.lowercase()

        listOf("profile/upload", "uploaddocument", "needy_profile_kyc", "eligibility", "kyc")
            .forEach { forbidden -> assertFalse("found $forbidden", source.contains(forbidden)) }
    }
}
