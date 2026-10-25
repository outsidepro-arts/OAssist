package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionServiceCatalogPolicyTest {

    @Test
    fun toInfos_filtersDisabledAppsAndBlankComponents() {
        val services = RecognitionServiceCatalogPolicy.toInfos(
            listOf(
                candidate("valid/Service", appEnabled = true),
                candidate("disabled/Service", appEnabled = false),
                candidate("", appEnabled = true),
            ),
        )

        assertEquals(listOf("valid/Service"), services.map { it.componentName })
    }

    @Test
    fun toInfos_usesServiceAndApplicationLabels() {
        val services = RecognitionServiceCatalogPolicy.toInfos(
            listOf(candidate("example.app/Recognizer", serviceLabel = "Voice", appLabel = "Example")),
        )

        assertEquals("Voice — Example", services.single().label)
    }

    @Test
    fun toInfos_disambiguatesSameLabelsAcrossPackages() {
        val services = RecognitionServiceCatalogPolicy.toInfos(
            listOf(
                candidate("first.pkg/Recognizer", serviceLabel = "Voice", appLabel = "Speech"),
                candidate("second.pkg/Recognizer", serviceLabel = "Voice", appLabel = "Speech"),
            ),
        )

        assertEquals(listOf("Voice — Speech (first.pkg)", "Voice — Speech (second.pkg)"), services.map { it.label })
    }

    @Test
    fun toInfos_disambiguatesServicesWithinOnePackage() {
        val services = RecognitionServiceCatalogPolicy.toInfos(
            listOf(
                candidate("one.pkg/First", serviceLabel = "Voice", appLabel = "Speech"),
                candidate("one.pkg/Second", serviceLabel = "Voice", appLabel = "Speech"),
            ),
        )

        assertEquals(listOf("Voice — Speech (one.pkg/First)", "Voice — Speech (one.pkg/Second)"), services.map { it.label })
    }

    @Test
    fun toInfos_ignoresDuplicateComponents() {
        val service = candidate("example.app/Recognizer")

        assertEquals(listOf(service.componentName), RecognitionServiceCatalogPolicy.toInfos(listOf(service, service)).map { it.componentName })
    }

    @Test
    fun availableComponent_fallsBackWhenSelectionIsMissingOrUnavailable() {
        val available = setOf("example.app/Recognizer")

        assertEquals("example.app/Recognizer", RecognitionServiceCatalogPolicy.availableComponent("example.app/Recognizer", available))
        assertNull(RecognitionServiceCatalogPolicy.availableComponent("removed.app/Recognizer", available))
        assertNull(RecognitionServiceCatalogPolicy.availableComponent(null, available))
        assertNull(RecognitionServiceCatalogPolicy.availableComponent(" ", available))
    }

    private fun candidate(
        componentName: String,
        serviceLabel: String = "",
        appLabel: String = "",
        appEnabled: Boolean = true,
    ) = RecognitionServiceCatalogPolicy.Candidate(
        componentName = componentName,
        serviceLabel = serviceLabel,
        appLabel = appLabel,
        appEnabled = appEnabled,
    )
}
