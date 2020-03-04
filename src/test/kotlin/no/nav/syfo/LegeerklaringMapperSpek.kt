package no.nav.syfo

import java.io.StringReader
import java.time.LocalDateTime
import java.util.UUID
import no.nav.helse.eiFellesformat.XMLEIFellesformat
import no.nav.syfo.model.toLegeerklaring
import no.nav.syfo.util.extractLegeerklaering
import no.nav.syfo.util.fellesformatUnmarshaller
import no.nav.syfo.util.getFileAsString
import org.amshove.kluent.shouldEqualTo
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object LegeerklaringMapperSpek : Spek({

    describe("Tester mappingen fra legeerklæring xml") {
        it("Tester mapping fra fellesformat til Legeerklaring format") {
            val felleformatLe = fellesformatUnmarshaller.unmarshal(
                StringReader(getFileAsString("src/test/resources/fellesformat_le.xml"))) as XMLEIFellesformat
            val legeerklaringxml = extractLegeerklaering(felleformatLe)

            val legeerklaering = legeerklaringxml.toLegeerklaring(
                legeerklaringId = UUID.randomUUID().toString(),
                fellesformat = felleformatLe,
                signaturDato = LocalDateTime.now()
            )

            legeerklaering.arbeidsavklaringspenger shouldEqualTo true
        }
    }
})
