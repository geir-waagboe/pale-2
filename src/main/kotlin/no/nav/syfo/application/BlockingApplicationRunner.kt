package no.nav.syfo.application

import kotlinx.coroutines.delay
import net.logstash.logback.argument.StructuredArguments
import net.logstash.logback.argument.StructuredArguments.fields
import no.nav.helse.eiFellesformat.XMLEIFellesformat
import no.nav.helse.eiFellesformat.XMLMottakenhetBlokk
import no.nav.helse.msgHead.XMLMsgHead
import no.nav.syfo.Environment
import no.nav.syfo.client.Pale2ReglerClient
import no.nav.syfo.handlestatus.handleDoctorNotFoundInPDL
import no.nav.syfo.handlestatus.handleDuplicateEdiloggid
import no.nav.syfo.handlestatus.handleDuplicateSM2013Content
import no.nav.syfo.handlestatus.handleFritekstfeltHarForMangeTegn
import no.nav.syfo.handlestatus.handlePatientNotFoundInPDL
import no.nav.syfo.handlestatus.handleStatusINVALID
import no.nav.syfo.handlestatus.handleStatusOK
import no.nav.syfo.handlestatus.handleTestFnrInProd
import no.nav.syfo.log
import no.nav.syfo.metrics.INCOMING_MESSAGE_COUNTER
import no.nav.syfo.metrics.MELDING_FEILET
import no.nav.syfo.metrics.REQUEST_TIME
import no.nav.syfo.metrics.VEDLEGG_COUNTER
import no.nav.syfo.model.ReceivedLegeerklaering
import no.nav.syfo.model.Status
import no.nav.syfo.model.kafka.LegeerklaeringKafkaMessage
import no.nav.syfo.model.toLegeerklaring
import no.nav.syfo.pdl.model.format
import no.nav.syfo.pdl.service.PdlPersonService
import no.nav.syfo.services.SamhandlerService
import no.nav.syfo.services.sha256hashstring
import no.nav.syfo.services.updateRedis
import no.nav.syfo.util.LoggingMeta
import no.nav.syfo.util.erTestFnr
import no.nav.syfo.util.extractLegeerklaering
import no.nav.syfo.util.extractOrganisationHerNumberFromSender
import no.nav.syfo.util.extractOrganisationNumberFromSender
import no.nav.syfo.util.extractOrganisationRashNumberFromSender
import no.nav.syfo.util.extractPersonIdent
import no.nav.syfo.util.extractSenderOrganisationName
import no.nav.syfo.util.fellesformatMarshaller
import no.nav.syfo.util.fellesformatUnmarshaller
import no.nav.syfo.util.get
import no.nav.syfo.util.getVedlegg
import no.nav.syfo.util.removeVedleggFromFellesformat
import no.nav.syfo.util.toString
import no.nav.syfo.util.wrapExceptions
import no.nav.syfo.vedlegg.google.BucketUploadService
import org.apache.kafka.clients.producer.KafkaProducer
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.exceptions.JedisConnectionException
import java.io.StringReader
import java.time.ZoneOffset
import java.util.UUID
import javax.jms.MessageConsumer
import javax.jms.MessageProducer
import javax.jms.Session
import javax.jms.TextMessage

private val sikkerlogg = LoggerFactory.getLogger("securelog")

class BlockingApplicationRunner {

    suspend fun run(
        applicationState: ApplicationState,
        inputconsumer: MessageConsumer,
        jedis: Jedis,
        session: Session,
        env: Environment,
        receiptProducer: MessageProducer,
        backoutProducer: MessageProducer,
        samhandlerService: SamhandlerService,
        pdlPersonService: PdlPersonService,
        arenaProducer: MessageProducer,
        aivenKafkaProducer: KafkaProducer<String, LegeerklaeringKafkaMessage>,
        pale2ReglerClient: Pale2ReglerClient,
        bucketUploadService: BucketUploadService
    ) {
        wrapExceptions {
            loop@ while (applicationState.ready) {
                val message = inputconsumer.receiveNoWait()
                if (message == null) {
                    delay(100)
                    continue
                }

                try {
                    val inputMessageText = when (message) {
                        is TextMessage -> message.text
                        else -> throw RuntimeException("Incoming message needs to be a byte message or text message")
                    }
                    val fellesformat =
                        fellesformatUnmarshaller.unmarshal(StringReader(inputMessageText)) as XMLEIFellesformat

                    val vedlegg = getVedlegg(fellesformat)
                    if (vedlegg.isNotEmpty()) {
                        VEDLEGG_COUNTER.inc()
                        removeVedleggFromFellesformat(fellesformat)
                    }
                    val fellesformatText = when (vedlegg.isNotEmpty()) {
                        true -> fellesformatMarshaller.toString(fellesformat)
                        false -> inputMessageText
                    }

                    val receiverBlock = fellesformat.get<XMLMottakenhetBlokk>()
                    val msgHead = fellesformat.get<XMLMsgHead>()
                    val ediLoggId = receiverBlock.ediLoggId
                    val msgId = msgHead.msgInfo.msgId
                    val legekontorOrgNr = extractOrganisationNumberFromSender(fellesformat)?.id
                    val legeerklaringxml = extractLegeerklaering(fellesformat)
                    val sha256String = sha256hashstring(legeerklaringxml)
                    val fnrPasient = extractPersonIdent(legeerklaringxml)!!
                    val legekontorOrgName = extractSenderOrganisationName(fellesformat)
                    val fnrLege = receiverBlock.avsenderFnrFraDigSignatur
                    val legekontorHerId = extractOrganisationHerNumberFromSender(fellesformat)?.id
                    val legekontorReshId = extractOrganisationRashNumberFromSender(fellesformat)?.id

                    val requestLatency = REQUEST_TIME.startTimer()

                    val loggingMeta = LoggingMeta(
                        mottakId = receiverBlock.ediLoggId,
                        orgNr = extractOrganisationNumberFromSender(fellesformat)?.id,
                        msgId = msgHead.msgInfo.msgId
                    )

                    log.info("Received message, {}", fields(loggingMeta))

                    sikkerlogg.info(
                        "Received message for pasient fnr {}, lege fnr: {}, {}",
                        fnrPasient,
                        fnrLege,
                        fields(loggingMeta)
                    )

                    INCOMING_MESSAGE_COUNTER.inc()

                    val tssIdent = samhandlerService.finnTssIdentOgStartSubscription(
                        fnrLege = fnrLege,
                        legekontorOrgName = legekontorOrgName,
                        legekontorHerId = legekontorHerId,
                        receiverBlock = receiverBlock,
                        msgHead = msgHead,
                        loggingMeta = loggingMeta
                    )

                    val redisSha256String = jedis.get(sha256String)
                    val redisEdiloggid = jedis.get(ediLoggId)

                    if (redisSha256String != null) {
                        handleDuplicateSM2013Content(
                            session, receiptProducer,
                            fellesformat, loggingMeta, env, redisSha256String
                        )
                        continue@loop
                    } else if (redisEdiloggid != null) {
                        handleDuplicateEdiloggid(
                            session, receiptProducer,
                            fellesformat, loggingMeta, env, redisEdiloggid
                        )
                        continue@loop
                    } else {
                        log.info("Slår opp behandler i PDL {}", fields(loggingMeta))
                        val behandler = pdlPersonService.getPdlPerson(fnrLege, loggingMeta)
                        log.info("Slår opp pasient i PDL {}", fields(loggingMeta))
                        val pasient = pdlPersonService.getPdlPerson(fnrPasient, loggingMeta)

                        if (pasient?.aktorId == null) {
                            handlePatientNotFoundInPDL(
                                session, receiptProducer, fellesformat, ediLoggId, jedis,
                                sha256String, env, loggingMeta
                            )
                            continue@loop
                        }
                        if (behandler?.aktorId == null) {
                            handleDoctorNotFoundInPDL(
                                session, receiptProducer, fellesformat, ediLoggId, jedis,
                                sha256String, env, loggingMeta
                            )
                            continue@loop
                        }
                        if (erTestFnr(fnrPasient) && env.cluster == "prod-gcp") {
                            handleTestFnrInProd(
                                session, receiptProducer, fellesformat,
                                ediLoggId, jedis, sha256String, env, loggingMeta
                            )
                            continue@loop
                        }
                        if (legeerklaringxml.diagnoseArbeidsuforhet?.statusPresens?.length != null &&
                            legeerklaringxml.diagnoseArbeidsuforhet.statusPresens.length > 15000
                        ) {
                            handleFritekstfeltHarForMangeTegn(
                                session, receiptProducer, fellesformat,
                                ediLoggId, jedis, sha256String, env, loggingMeta, "StatusPresens"
                            )
                            continue@loop
                        }
                        if (legeerklaringxml.diagnoseArbeidsuforhet?.symptomerBehandling?.length != null &&
                            legeerklaringxml.diagnoseArbeidsuforhet.symptomerBehandling.length > 15000
                        ) {
                            handleFritekstfeltHarForMangeTegn(
                                session, receiptProducer, fellesformat,
                                ediLoggId, jedis, sha256String, env, loggingMeta, "SymptomerBehandling"
                            )
                            continue@loop
                        }

                        val legeerklaring = legeerklaringxml.toLegeerklaring(
                            legeerklaringId = UUID.randomUUID().toString(),
                            fellesformat = fellesformat,
                            signaturDato = msgHead.msgInfo.genDate,
                            behandlerNavn = behandler.navn.format()
                        )

                        val receivedLegeerklaering = ReceivedLegeerklaering(
                            legeerklaering = legeerklaring,
                            personNrPasient = fnrPasient,
                            pasientAktoerId = pasient.aktorId,
                            personNrLege = fnrLege,
                            legeAktoerId = behandler.aktorId,
                            navLogId = ediLoggId,
                            msgId = msgId,
                            legekontorOrgNr = legekontorOrgNr,
                            legekontorOrgName = legekontorOrgName,
                            legekontorHerId = legekontorHerId,
                            legekontorReshId = legekontorReshId,
                            mottattDato = receiverBlock.mottattDatotid.toGregorianCalendar().toZonedDateTime()
                                .withZoneSameInstant(
                                    ZoneOffset.UTC
                                ).toLocalDateTime(),
                            fellesformat = fellesformatText,
                            tssid = tssIdent
                        )

                        val validationResult =
                            pale2ReglerClient.executeRuleValidation(receivedLegeerklaering, loggingMeta)

                        val vedleggListe: List<String> = if (vedlegg.isNotEmpty()) {
                            bucketUploadService.uploadVedlegg(
                                vedlegg = vedlegg,
                                legeerklaering = receivedLegeerklaering,
                                xmleiFellesformat = fellesformat,
                                loggingMeta = loggingMeta
                            )
                        } else {
                            emptyList()
                        }

                        val uploadLegeerklaering =
                            bucketUploadService.uploadLegeerklaering(receivedLegeerklaering, loggingMeta)
                        val legeerklaeringKafkaMessage =
                            LegeerklaeringKafkaMessage(uploadLegeerklaering, validationResult, vedleggListe)

                        when (validationResult.status) {
                            Status.OK -> handleStatusOK(
                                session = session,
                                receiptProducer = receiptProducer,
                                fellesformat = fellesformat,
                                arenaProducer = arenaProducer,
                                tssId = tssIdent,
                                ediLoggId = ediLoggId,
                                fnrLege = fnrLege,
                                legeerklaring = legeerklaring,
                                loggingMeta = loggingMeta,
                                aivenKafkaProducer = aivenKafkaProducer,
                                topic = env.legeerklaringTopic,
                                legeerklaringKafkaMessage = legeerklaeringKafkaMessage,
                                apprecQueueName = env.apprecQueueName
                            )

                            Status.INVALID -> handleStatusINVALID(
                                validationResult = validationResult,
                                session = session,
                                receiptProducer = receiptProducer,
                                fellesformat = fellesformat,
                                loggingMeta = loggingMeta,
                                aivenKafkaProducer = aivenKafkaProducer,
                                topic = env.legeerklaringTopic,
                                legeerklaringKafkaMessage = legeerklaeringKafkaMessage,
                                apprecQueueName = env.apprecQueueName
                            )
                        }

                        val currentRequestLatency = requestLatency.observeDuration()

                        log.info(
                            "Finished message got outcome {}, {}, {},processing took {}s",
                            StructuredArguments.keyValue("status", validationResult.status),
                            StructuredArguments.keyValue(
                                "ruleHits",
                                validationResult.ruleHits.joinToString(", ", "(", ")") { it.ruleName }
                            ),
                            StructuredArguments.keyValue("latency", currentRequestLatency),
                            fields(loggingMeta)
                        )
                        updateRedis(jedis, ediLoggId, sha256String)
                    }
                } catch (jedisException: JedisConnectionException) {
                    log.error(
                        "Exception caught, redis issue while handling message, sending to backout",
                        jedisException
                    )
                    backoutProducer.send(message)
                    MELDING_FEILET.inc()
                    log.error("Setting applicationState.alive to false")
                    applicationState.alive = false
                } catch (e: Exception) {
                    log.error("Exception caught while handling message, sending to backout: ", e)
                    backoutProducer.send(message)
                    MELDING_FEILET.inc()
                } finally {
                    message.acknowledge()
                }
            }
        }
    }

    fun fellesformatTilString(fellesformat: XMLEIFellesformat): String =
        fellesformatMarshaller.toString(fellesformat)
}
