package no.nav.syfo.client

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class KuhrSarClientTest {
    val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    internal fun `Finner en samhandler når det bare er inaktivte samhandlere`() {
        val samhandlerMedNavn: List<Samhandler> = objectMapper.readValue(
            KuhrSarClientTest::class.java.getResourceAsStream("/kuhr_sahr_response_inaktive.json")!!.readBytes()
                .toString(Charsets.UTF_8),
        )

        val match = samhandlerMatchingPaaOrganisjonsNavn(samhandlerMedNavn, "Testlegesenteret")
        assertEquals("Testlegesenteret - org nr", match?.samhandlerPraksis?.navn)
    }
}
