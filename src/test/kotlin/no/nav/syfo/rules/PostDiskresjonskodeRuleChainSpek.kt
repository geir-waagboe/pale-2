package no.nav.syfo.rules

import io.mockk.mockk
import no.nav.syfo.model.Legeerklaering
import org.amshove.kluent.shouldEqual
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object PostDiskresjonskodeRuleChainSpek : Spek({

    val legeerklaring = mockk<Legeerklaering>()

    fun ruleData(
        legeerklaring: Legeerklaering,
        diskresjonskode: String?
    ): RuleData<String?> = RuleData(legeerklaring, diskresjonskode)

    describe("Testing validation rules and checking the rule outcomes") {

        it("Should check rule PASIENTEN_HAR_KODE_6, should trigger rule") {
            val diskresjonskode = "SPSF"

            PostDiskresjonskodeRuleChain.PASIENTEN_HAR_KODE_6(ruleData(legeerklaring, diskresjonskode)) shouldEqual true
        }

        it("Should check rule PASIENTEN_HAR_KODE_6, should NOT trigger rule") {
            val diskresjonskode = "SPFO"

            PostDiskresjonskodeRuleChain.PASIENTEN_HAR_KODE_6(ruleData(legeerklaring, diskresjonskode)) shouldEqual false
        }
    }
})
