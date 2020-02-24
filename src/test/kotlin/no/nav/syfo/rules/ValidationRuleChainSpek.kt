package no.nav.syfo.rules

import com.devskiller.jfairy.Fairy
import com.devskiller.jfairy.producer.person.PersonProperties
import com.devskiller.jfairy.producer.person.PersonProvider
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import no.nav.helse.legeerklaering.Legeerklaring
import no.nav.syfo.model.RuleMetadata
import no.nav.syfo.validation.validatePersonAndDNumber
import org.amshove.kluent.shouldEqual
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

val fairy: Fairy = Fairy.create() // (Locale("no", "NO"))
val personNumberDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyy")

object ValidationRuleChainSpek : Spek({
    fun ruleData(
        legeerklaring: Legeerklaring = Legeerklaring(),
        receivedDate: LocalDateTime = LocalDateTime.now(),
        signatureDate: LocalDateTime = LocalDateTime.now(),
        patientPersonNumber: String = "1234567891",
        legekontorOrgNr: String = "123456789",
        tssid: String? = "1314445"
    ): RuleData<RuleMetadata> = RuleData(legeerklaring,
        RuleMetadata(signatureDate, receivedDate, patientPersonNumber, legekontorOrgNr, tssid)
    )

    describe("Testing validation rules and checking the rule outcomes") {

        it("Should check rule UGYLDIG_FNR_LENGDE, should trigger rule") {
            ValidationRuleChain.UGYLDIG_FNR_LENGDE_PASIENT(
                ruleData(Legeerklaring(), patientPersonNumber = "3006310441")) shouldEqual true
        }

        it("Should check rule UGYLDIG_FNR_LENGDE, should NOT trigger rule") {
            ValidationRuleChain.UGYLDIG_FNR_LENGDE_PASIENT(
                ruleData(Legeerklaring(), patientPersonNumber = "04030350265")) shouldEqual false
        }

        it("Should check rule UGYLDIG_FNR_PASIENT, should trigger rule") {
            ValidationRuleChain.UGYLDIG_FNR_PASIENT(
                ruleData(Legeerklaring(), patientPersonNumber = "30063104424")) shouldEqual true
        }

        it("Should check rule UGYLDIG_FNR, should NOT trigger rule") {
            ValidationRuleChain.UGYLDIG_FNR_PASIENT(
                ruleData(Legeerklaring(), patientPersonNumber = "04030350265")) shouldEqual false
        }

        it("Should check rule PASIENT_YNGRE_ENN_13,should trigger rule") {
            val person = fairy.person(PersonProperties.ageBetween(PersonProvider.MIN_AGE, 12))

            ValidationRuleChain.PASIENT_YNGRE_ENN_13(ruleData(
                Legeerklaring(),
                patientPersonNumber = generatePersonNumber(person.dateOfBirth, false)
            )) shouldEqual true
        }

        it("Should check rule PASIENT_YNGRE_ENN_13,should NOT trigger rule") {
            val person = fairy.person(
                PersonProperties.ageBetween(13, 70))

            ValidationRuleChain.PASIENT_YNGRE_ENN_13(ruleData(
                Legeerklaring(),
                patientPersonNumber = generatePersonNumber(person.dateOfBirth, false)
            )) shouldEqual false
        }

        it("UGYLDIG_ORGNR_LENGDE should trigger on when orgnr lengt is not 9") {

            ValidationRuleChain.UGYLDIG_ORGNR_LENGDE(ruleData(Legeerklaring(), legekontorOrgNr = "1234567890")) shouldEqual true
        }

        it("UGYLDIG_ORGNR_LENGDE should not trigger on when orgnr is 9") {

            ValidationRuleChain.UGYLDIG_ORGNR_LENGDE(ruleData(Legeerklaring(), legekontorOrgNr = "123456789")) shouldEqual false
        }
    }
})

fun generatePersonNumber(bornDate: LocalDate, useDNumber: Boolean = false): String {
    val personDate = bornDate.format(personNumberDateFormat).let {
        if (useDNumber) "${it[0] + 4}${it.substring(1)}" else it
    }
    return (if (bornDate.year >= 2000) (75011..99999) else (11111..50099))
        .map { "$personDate$it" }
        .first {
            validatePersonAndDNumber(it)
        }
}
