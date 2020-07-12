package no.nav.syfo

import java.io.StringReader
import no.nav.helse.eiFellesformat.XMLEIFellesformat
import no.nav.helse.eiFellesformat.XMLMottakenhetBlokk
import no.nav.helse.msgHead.XMLMsgHead
import no.nav.syfo.application.BlockingApplicationRunner
import no.nav.syfo.util.fellesformatUnmarshaller
import no.nav.syfo.util.get
import no.nav.syfo.util.getFileAsString
import org.amshove.kluent.shouldEqual
import org.junit.Test

internal class DumpTopicTest {

    @Test
    internal fun `Skal kunne deserialisere melding fra dumptopic`() {
        val fellesformatOriginal = fellesformatUnmarshaller.unmarshal(StringReader(getFileAsString("src/test/resources/fellesformat_le.xml"))) as XMLEIFellesformat
        val fellesformatSomString = BlockingApplicationRunner().fellesformatTilString(fellesformatOriginal)

        val fellesformat = fellesformatUnmarshaller.unmarshal(StringReader(fellesformatSomString)) as XMLEIFellesformat

        val receiverBlock = fellesformat.get<XMLMottakenhetBlokk>()
        val msgHead = fellesformat.get<XMLMsgHead>()
        val ediLoggId = receiverBlock.ediLoggId
        val msgId = msgHead.msgInfo.msgId

        ediLoggId shouldEqual "FiktivTestdata0001"
        msgId shouldEqual "05565fb0-cd7e-410d-bc1f-e1e918df2eac"
    }
}
