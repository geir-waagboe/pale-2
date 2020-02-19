package no.nav.syfo

import com.ctc.wstx.exc.WstxException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.auth.Auth
import io.ktor.client.features.auth.providers.basic
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.hotspot.DefaultExports
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.lang.IllegalStateException
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.GregorianCalendar
import java.util.concurrent.TimeUnit
import javax.jms.MessageConsumer
import javax.jms.MessageProducer
import javax.jms.Session
import javax.jms.TextMessage
import javax.xml.bind.Marshaller
import javax.xml.datatype.DatatypeFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.logstash.logback.argument.StructuredArguments
import net.logstash.logback.argument.StructuredArguments.fields
import no.nav.helse.apprecV1.XMLAppRec
import no.nav.helse.apprecV1.XMLCV as AppRecCV
import no.nav.helse.eiFellesformat.XMLEIFellesformat
import no.nav.helse.eiFellesformat.XMLMottakenhetBlokk
import no.nav.helse.legeerklaering.AktueltTiltak
import no.nav.helse.legeerklaering.Arbeidssituasjon
import no.nav.helse.legeerklaering.DiagnoseArbeidsuforhet
import no.nav.helse.legeerklaering.Enkeltdiagnose
import no.nav.helse.legeerklaering.ForslagTiltak
import no.nav.helse.legeerklaering.Legeerklaring
import no.nav.helse.legeerklaering.PlanUtredBehandle
import no.nav.helse.legeerklaering.VurderingFunksjonsevne
import no.nav.helse.msgHead.XMLHealthcareProfessional
import no.nav.helse.msgHead.XMLIdent
import no.nav.helse.msgHead.XMLMsgHead
import no.nav.syfo.application.ApplicationServer
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.createApplicationEngine
import no.nav.syfo.apprec.ApprecStatus
import no.nav.syfo.apprec.createApprec
import no.nav.syfo.client.AktoerIdClient
import no.nav.syfo.client.DokArkivClient
import no.nav.syfo.client.LegeSuspensjonClient
import no.nav.syfo.client.Norg2Client
import no.nav.syfo.client.PdfgenClient
import no.nav.syfo.client.SakClient
import no.nav.syfo.client.SarClient
import no.nav.syfo.client.StsOidcClient
import no.nav.syfo.client.createJournalpostPayload
import no.nav.syfo.client.findBestSamhandlerPraksis
import no.nav.syfo.helpers.retry
import no.nav.syfo.metrics.APPREC_COUNTER
import no.nav.syfo.metrics.INCOMING_MESSAGE_COUNTER
import no.nav.syfo.metrics.INVALID_MESSAGE_NO_NOTICE
import no.nav.syfo.metrics.REQUEST_TIME
import no.nav.syfo.metrics.RULE_HIT_STATUS_COUNTER
import no.nav.syfo.model.Arbeidsgiver
import no.nav.syfo.model.Diagnose
import no.nav.syfo.model.ForslagTilTiltak
import no.nav.syfo.model.FunksjonsOgArbeidsevne
import no.nav.syfo.model.Henvisning
import no.nav.syfo.model.Kontakt
import no.nav.syfo.model.Pasient
import no.nav.syfo.model.PdfPayload
import no.nav.syfo.model.Plan
import no.nav.syfo.model.Prognose
import no.nav.syfo.model.RuleInfo
import no.nav.syfo.model.RuleMetadata
import no.nav.syfo.model.Signatur
import no.nav.syfo.model.Status
import no.nav.syfo.model.SykdomsOpplysninger
import no.nav.syfo.model.ValidationResult
import no.nav.syfo.mq.connectionFactory
import no.nav.syfo.mq.consumerForQueue
import no.nav.syfo.mq.producerForQueue
import no.nav.syfo.rules.HPRRuleChain
import no.nav.syfo.rules.LegesuspensjonRuleChain
import no.nav.syfo.rules.PostDiskresjonskodeRuleChain
import no.nav.syfo.rules.Rule
import no.nav.syfo.rules.RuleData
import no.nav.syfo.rules.ValidationRuleChain
import no.nav.syfo.rules.executeFlow
import no.nav.syfo.services.DiskresjonskodeService
import no.nav.syfo.services.FindNAVKontorService
import no.nav.syfo.ws.createPort
import no.nav.tjeneste.pip.diskresjonskode.DiskresjonskodePortType
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.binding.ArbeidsfordelingV1
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.informasjon.ArbeidsfordelingKriterier
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.informasjon.Diskresjonskoder
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.informasjon.Geografi
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.informasjon.Oppgavetyper
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.informasjon.Tema
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.meldinger.FinnBehandlendeEnhetListeRequest
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.meldinger.FinnBehandlendeEnhetListeResponse
import no.nav.tjeneste.virksomhet.person.v3.binding.PersonV3
import no.nav.tjeneste.virksomhet.person.v3.informasjon.GeografiskTilknytning
import no.nav.tjeneste.virksomhet.person.v3.informasjon.NorskIdent
import no.nav.tjeneste.virksomhet.person.v3.informasjon.PersonIdent
import no.nav.tjeneste.virksomhet.person.v3.informasjon.Personidenter
import no.nav.tjeneste.virksomhet.person.v3.meldinger.HentGeografiskTilknytningRequest
import no.nav.tjeneste.virksomhet.person.v3.meldinger.HentGeografiskTilknytningResponse
import no.nhn.schemas.reg.hprv2.IHPR2Service
import no.nhn.schemas.reg.hprv2.Person as HPRPerson
import org.apache.cxf.binding.soap.SoapMessage
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor
import org.apache.cxf.message.Message
import org.apache.cxf.phase.Phase
import org.apache.cxf.ws.addressing.WSAddressingFeature
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.exceptions.JedisConnectionException

val objectMapper: ObjectMapper = ObjectMapper()
    .registerModule(JavaTimeModule())
    .registerKotlinModule()
    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

val datatypeFactory: DatatypeFactory = DatatypeFactory.newInstance()

val log: Logger = LoggerFactory.getLogger("no.nav.syfo.pale-2")

const val NAV_OPPFOLGING_UTLAND_KONTOR_NR = "0393"

@KtorExperimentalAPI
fun main() {
    val env = Environment()
    val credentials = objectMapper.readValue<VaultCredentials>(
        Paths.get("/var/run/secrets/nais.io/vault/credentials.json").toFile()
    )

    val applicationState = ApplicationState()
    val applicationEngine = createApplicationEngine(
        env,
        applicationState
    )

    val applicationServer = ApplicationServer(applicationEngine, applicationState)
    applicationServer.start()

    DefaultExports.initialize()

    val httpClientMedBasicAuth = HttpClient(Apache) {
        install(Auth) {
            basic {
                username = credentials.serviceuserUsername
                password = credentials.serviceuserPassword
                sendWithoutRequest = true
            }
        }
        install(JsonFeature) {
            serializer = JacksonSerializer {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
    }

    val httpClient = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = JacksonSerializer {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
    }

    val oidcClient = StsOidcClient(credentials.serviceuserUsername, credentials.serviceuserPassword)
    val aktoerIdClient = AktoerIdClient(env.aktoerregisterV1Url, oidcClient, httpClient)

    val sarClient = SarClient(env.kuhrSarApiUrl, httpClientMedBasicAuth)
    val pdfgenClient = PdfgenClient(env.pdfgen, httpClient)
    val sakClient = SakClient(env.opprettSakUrl, oidcClient, httpClient)
    val dokArkivClient = DokArkivClient(env.dokArkivUrl, oidcClient, httpClient)
    val diskresjonskodePortType: DiskresjonskodePortType = createPort(env.diskresjonskodeEndpointUrl) {
        port { withSTS(credentials.serviceuserUsername, credentials.serviceuserPassword, env.securityTokenServiceURL) }
    }
    val diskresjonskodeService = DiskresjonskodeService(diskresjonskodePortType)
    val norg2Client = Norg2Client(env.norg2V1EndpointURL, httpClient)
    val legeSuspensjonClient = LegeSuspensjonClient(
        env.legeSuspensjonEndpointURL,
        credentials,
        oidcClient,
        httpClient
    )

    val helsepersonellV1 = createPort<IHPR2Service>(env.helsepersonellv1EndpointURL) {
        proxy {
            // TODO: Contact someone about this hacky workaround
            // talk to HDIR about HPR about they claim to send a ISO-8859-1 but its really UTF-8 payload
            val interceptor = object : AbstractSoapInterceptor(Phase.RECEIVE) {
                override fun handleMessage(message: SoapMessage?) {
                    if (message != null)
                        message[Message.ENCODING] = "utf-8"
                }
            }

            inInterceptors.add(interceptor)
            inFaultInterceptors.add(interceptor)
            features.add(WSAddressingFeature())
        }

        port {
            withSTS(
                credentials.serviceuserUsername,
                credentials.serviceuserPassword,
                env.securityTokenServiceURL
            )
        }
    }

    val personV3 = createPort<PersonV3>(env.personV3EndpointURL) {
        port {
            withSTS(
                credentials.serviceuserUsername,
                credentials.serviceuserPassword,
                env.securityTokenServiceURL
            )
        }
    }

    val arbeidsfordelingV1 = createPort<ArbeidsfordelingV1>(env.arbeidsfordelingV1EndpointURL) {
        port { withSTS(credentials.serviceuserUsername, credentials.serviceuserPassword, env.securityTokenServiceURL) }
    }

    launchListeners(
        applicationState, env, sarClient,
        aktoerIdClient, credentials, helsepersonellV1,
        legeSuspensjonClient, pdfgenClient, sakClient, dokArkivClient, diskresjonskodeService,
        personV3, norg2Client, arbeidsfordelingV1
    )
}

fun createListener(applicationState: ApplicationState, action: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch {
        try {
            action()
        } catch (e: TrackableException) {
            log.error("En uhåndtert feil oppstod, applikasjonen restarter {}", e.cause)
        } finally {
            applicationState.alive = false
        }
    }

@KtorExperimentalAPI
fun launchListeners(
    applicationState: ApplicationState,
    env: Environment,
    kuhrSarClient: SarClient,
    aktoerIdClient: AktoerIdClient,
    credentials: VaultCredentials,
    helsepersonellv1: IHPR2Service,
    legeSuspensjonClient: LegeSuspensjonClient,
    pdfgenClient: PdfgenClient,
    sakClient: SakClient,
    dokArkivClient: DokArkivClient,
    diskresjonskodeService: DiskresjonskodeService,
    personV3: PersonV3,
    norg2Client: Norg2Client,
    arbeidsfordelingV1: ArbeidsfordelingV1
) {
    createListener(applicationState) {
        connectionFactory(env).createConnection(credentials.mqUsername, credentials.mqPassword).use { connection ->
            Jedis(env.redishost, 6379).use { jedis ->
                connection.start()
                val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)

                val inputconsumer = session.consumerForQueue(env.inputQueueName)
                val receiptProducer = session.producerForQueue(env.apprecQueueName)
                val backoutProducer = session.producerForQueue(env.inputBackoutQueueName)
                val arenaProducer = session.producerForQueue(env.arenaQueueName)

                applicationState.ready = true

                blockingApplicationLogic(
                    applicationState,
                    inputconsumer,
                    jedis,
                    session,
                    env,
                    receiptProducer,
                    backoutProducer,
                    kuhrSarClient,
                    aktoerIdClient,
                    credentials,
                    helsepersonellv1,
                    legeSuspensjonClient,
                    pdfgenClient,
                    sakClient,
                    dokArkivClient,
                    diskresjonskodeService,
                    arenaProducer,
                    personV3,
                    norg2Client,
                    arbeidsfordelingV1
                )
            }
        }
    }
}

@KtorExperimentalAPI
suspend fun blockingApplicationLogic(
    applicationState: ApplicationState,
    inputconsumer: MessageConsumer,
    jedis: Jedis,
    session: Session,
    env: Environment,
    receiptProducer: MessageProducer,
    backoutProducer: MessageProducer,
    kuhrSarClient: SarClient,
    aktoerIdClient: AktoerIdClient,
    credentials: VaultCredentials,
    helsepersonellv1: IHPR2Service,
    legeSuspensjonClient: LegeSuspensjonClient,
    pdfgenClient: PdfgenClient,
    sakClient: SakClient,
    dokArkivClient: DokArkivClient,
    diskresjonskodeService: DiskresjonskodeService,
    arenaProducer: MessageProducer,
    personV3: PersonV3,
    norg2Client: Norg2Client,
    arbeidsfordelingV1: ArbeidsfordelingV1
) = coroutineScope {
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
                val receiverBlock = fellesformat.get<XMLMottakenhetBlokk>()
                val msgHead = fellesformat.get<XMLMsgHead>()
                val ediLoggId = receiverBlock.ediLoggId
                val msgId = msgHead.msgInfo.msgId
                val legekontorOrgNr = extractOrganisationNumberFromSender(fellesformat)?.id
                val legeerklaring = extractLegeerklaering(fellesformat)
                val sha256String = sha256hashstring(legeerklaring)
                val personNumberPatient = extractPersonIdent(legeerklaring)!!
                val legekontorOrgName = extractSenderOrganisationName(fellesformat)
                val personNumberDoctor = receiverBlock.avsenderFnrFraDigSignatur
                val legekontorHerId = extractOrganisationHerNumberFromSender(fellesformat)?.id

                INCOMING_MESSAGE_COUNTER.inc()
                val requestLatency = REQUEST_TIME.startTimer()

                val loggingMeta = LoggingMeta(
                    mottakId = receiverBlock.ediLoggId,
                    orgNr = extractOrganisationNumberFromSender(fellesformat)?.id,
                    msgId = msgHead.msgInfo.msgId
                )

                log.info("Received message, {}", fields(loggingMeta))

                val aktoerIds = aktoerIdClient.getAktoerIds(
                    listOf(
                        personNumberDoctor,
                        personNumberPatient
                    ),
                    credentials.serviceuserUsername, loggingMeta
                )

                val samhandlerInfo = kuhrSarClient.getSamhandler(personNumberDoctor)
                val samhandlerPraksis = findBestSamhandlerPraksis(
                    samhandlerInfo,
                    legekontorOrgName,
                    legekontorHerId,
                    loggingMeta
                )?.samhandlerPraksis

                try {
                    val redisSha256String = jedis.get(sha256String)
                    val redisEdiloggid = jedis.get(ediLoggId)

                    if (redisSha256String != null) {
                        log.warn(
                            "Message with {} marked as duplicate {}",
                            StructuredArguments.keyValue("originalEdiLoggId", redisSha256String),
                            fields(loggingMeta)
                        )
                        sendReceipt(
                            session, receiptProducer, fellesformat, ApprecStatus.avvist, listOf(
                                createApprecError(
                                    "Duplikat! - Denne sykmeldingen er mottatt tidligere. " +
                                            "Skal ikke sendes på nytt."
                                )
                            )
                        )
                        log.info("Apprec Receipt sent to {}, {}", env.apprecQueueName, fields(loggingMeta))
                        continue
                    } else if (redisEdiloggid != null) {
                        log.warn(
                            "Message with {} marked as duplicate, {}",
                            StructuredArguments.keyValue("originalEdiLoggId", redisEdiloggid), fields(loggingMeta)
                        )
                        sendReceipt(
                            session, receiptProducer, fellesformat, ApprecStatus.avvist, listOf(
                                createApprecError(
                                    "Duplikat! - Denne sykmeldingen er mottatt tidligere. " +
                                            "Skal ikke sendes på nytt."
                                )
                            )
                        )
                        log.info("Apprec Receipt sent to {}, {}", env.apprecQueueName, fields(loggingMeta))
                        continue
                    } else {
                        jedis.setex(ediLoggId, TimeUnit.DAYS.toSeconds(7).toInt(), ediLoggId)
                        jedis.setex(sha256String, TimeUnit.DAYS.toSeconds(7).toInt(), ediLoggId)
                    }
                } catch (connectionException: JedisConnectionException) {
                    log.warn("Unable to contact redis, will allow possible duplicates.", connectionException)
                }

                val patientIdents = aktoerIds[personNumberPatient]
                val doctorIdents = aktoerIds[personNumberDoctor]

                if (patientIdents == null || patientIdents.feilmelding != null) {
                    log.info(
                        "Patient not found i aktorRegister {}, {}", fields(loggingMeta),
                        StructuredArguments.keyValue(
                            "errorMessage",
                            patientIdents?.feilmelding ?: "No response for FNR"
                        )
                    )
                    sendReceipt(
                        session, receiptProducer, fellesformat, ApprecStatus.avvist, listOf(
                            createApprecError("Pasienten er ikkje registrert i folkeregisteret")
                        )
                    )
                    log.info("Apprec Receipt sent to {}, {}", env.apprecQueueName, fields(loggingMeta))
                    INVALID_MESSAGE_NO_NOTICE.inc()
                    continue@loop
                }
                if (doctorIdents == null || doctorIdents.feilmelding != null) {
                    log.info(
                        "Doctor not found i aktorRegister {}, {}", fields(loggingMeta),
                        StructuredArguments.keyValue("errorMessage", doctorIdents?.feilmelding ?: "No response for FNR")
                    )
                    sendReceipt(
                        session, receiptProducer, fellesformat, ApprecStatus.avvist, listOf(
                            createApprecError("Behandler er ikkje registrert i folkeregisteret")
                        )
                    )
                    log.info("Apprec Receipt sent to {}, {}", env.apprecQueueName, fields(loggingMeta))
                    INVALID_MESSAGE_NO_NOTICE.inc()
                    continue@loop
                }

                val validationRuleResults: List<Rule<Any>> = listOf<List<Rule<RuleData<RuleMetadata>>>>(
                    ValidationRuleChain.values().toList()
                ).flatten().executeFlow(
                    legeerklaring, RuleMetadata(
                        receivedDate = receiverBlock.mottattDatotid.toGregorianCalendar().toZonedDateTime().toLocalDateTime(),
                        signatureDate = msgHead.msgInfo.genDate,
                        patientPersonNumber = personNumberPatient,
                        legekontorOrgnr = legekontorOrgNr,
                        tssid = samhandlerPraksis?.tss_ident
                    )
                )

                val patientDiskresjonskodeDeferred =
                    async { diskresjonskodeService.hentDiskresjonskode(personNumberPatient) }
                val patientDiskresjonskode = patientDiskresjonskodeDeferred.await()

                val signaturDatoString = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(msgHead.msgInfo.genDate)

                val doctorSuspend =
                    legeSuspensjonClient.checkTherapist(personNumberDoctor, msgId, signaturDatoString).suspendert

                val doctor = fetchDoctor(helsepersonellv1, personNumberDoctor).await()

                val results = listOf(
                    ValidationRuleChain.values().executeFlow(
                    legeerklaring, RuleMetadata(
                        receivedDate = receiverBlock.mottattDatotid.toGregorianCalendar().toZonedDateTime().toLocalDateTime(),
                        signatureDate = msgHead.msgInfo.genDate,
                        patientPersonNumber = personNumberPatient,
                        legekontorOrgnr = legekontorOrgNr,
                        tssid = samhandlerPraksis?.tss_ident
                    )
                ),
                    PostDiskresjonskodeRuleChain.values().executeFlow(legeerklaring, patientDiskresjonskode),
                    HPRRuleChain.values().executeFlow(legeerklaring, doctor),
                    LegesuspensjonRuleChain.values().executeFlow(legeerklaring, doctorSuspend)
                ).flatten()

                log.info("Rules hit {}, {}", results.map { it.name }, fields(loggingMeta))

                val findNAVKontorService = FindNAVKontorService(
                    personNumberPatient,
                    personV3,
                    norg2Client,
                    arbeidsfordelingV1,
                    patientDiskresjonskode,
                    loggingMeta
                )

                val behandlendeEnhet = findNAVKontorService.finnBehandlendeEnhet()
                val lokaltNavkontor = findNAVKontorService.finnLokaltNavkontor()

                val legeerklaering = extractLegeerklaering(fellesformat)
                val plan = legeerklaering.planUtredBehandle
                val forslagTiltak = legeerklaering.forslagTiltak
                val typeLegeerklaering = legeerklaering.legeerklaringGjelder[0].typeLegeerklaring.toInt()
                val funksjonsevne = legeerklaering.vurderingFunksjonsevne
                val prognose = legeerklaering.prognose
                val healthcareProfessional =
                    fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation?.healthcareProfessional

                val validationResult = validationResult(results)
                RULE_HIT_STATUS_COUNTER.labels(validationResult.status.name).inc()

                val pdfPayload = createPdfPayload(
                    legeerklaering,
                    plan,
                    forslagTiltak,
                    typeLegeerklaering,
                    funksjonsevne,
                    prognose,
                    healthcareProfessional,
                    fellesformat,
                    validationResult
                )

                val sakid = sakClient.findOrCreateSak(
                    patientIdents.identer!!.first().ident, msgId,
                    loggingMeta
                ).id.toString()

                val pdf = pdfgenClient.createPDF(pdfPayload)
                log.info("PDF generated {}", fields(loggingMeta))

                val journalpostPayload = createJournalpostPayload(
                    legeerklaering,
                    sakid,
                    pdf,
                    msgHead,
                    receiverBlock,
                    validationResult
                )

                val journalpost = dokArkivClient.createJournalpost(journalpostPayload, loggingMeta)
                log.info(
                    "Message successfully persisted in Joark {}, {}",
                    StructuredArguments.keyValue("journalpostId", journalpost.journalpostId),
                    fields(loggingMeta)
                )

                // Sperrekode 6 is a special case and is not sent to Arena, it should still create a task in Gosys
                if (validationResult.ruleHits.any { it.ruleName == PostDiskresjonskodeRuleChain.PASIENTEN_HAR_KODE_6.name }) {
                    log.info("Not sending message to arena, {}", fields(loggingMeta))
                } else {
                    log.info("Sending message to arena, {}", fields(loggingMeta))
                    // sendArenaInfo(arenaProducer, session, fellesformat, validationResult)
                }
                val currentRequestLatency = requestLatency.observeDuration()
            } catch (e: Exception) {
                log.error("Exception caught while handling message, sending to backout, {}", e)
                backoutProducer.send(message)
            }
        }
    }
}

fun extractOrganisationNumberFromSender(fellesformat: XMLEIFellesformat): XMLIdent? =
    fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation.ident.find {
        it.typeId.v == "ENH"
    }

fun extractOrganisationHerNumberFromSender(fellesformat: XMLEIFellesformat): XMLIdent? =
    fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation.ident.find {
        it.typeId.v == "HER"
    }

fun extractLegeerklaering(fellesformat: XMLEIFellesformat): Legeerklaring =
    fellesformat.get<XMLMsgHead>().document[0].refDoc.content.any[0] as Legeerklaring

inline fun <reified T> XMLEIFellesformat.get() = this.any.find { it is T } as T

fun createApprecError(textToTreater: String): AppRecCV = AppRecCV().apply {
    dn = textToTreater
    v = "2.16.578.1.12.4.1.1.8221"
    s = "X99"
}

fun sha256hashstring(legeerklaring: Legeerklaring): String =
    MessageDigest.getInstance("SHA-256")
        .digest(objectMapper.writeValueAsBytes(legeerklaring))
        .fold("") { str, it -> str + "%02x".format(it) }

fun sendReceipt(
    session: Session,
    receiptProducer: MessageProducer,
    fellesformat: XMLEIFellesformat,
    apprecStatus: ApprecStatus,
    apprecErrors: List<AppRecCV> = listOf()
) {
    APPREC_COUNTER.inc()
    receiptProducer.send(session.createTextMessage().apply {
        val apprec = createApprec(fellesformat, apprecStatus)
        apprec.get<XMLAppRec>().error.addAll(apprecErrors)
        text = apprecMarshaller.toString(apprec)
    })
}

fun Marshaller.toString(input: Any): String = StringWriter().use {
    marshal(input, it)
    it.toString()
}

fun extractPersonIdent(legeerklaering: Legeerklaring): String? =
    legeerklaering.pasientopplysninger.pasient.fodselsnummer

fun extractSenderOrganisationName(fellesformat: XMLEIFellesformat): String =
    fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation?.organisationName ?: ""

fun CoroutineScope.fetchDoctor(hprService: IHPR2Service, doctorIdent: String): Deferred<HPRPerson> = async {
    retry(
        callName = "hpr_hent_person_med_personnummer",
        retryIntervals = arrayOf(500L, 1000L, 3000L, 5000L, 10000L),
        legalExceptions = *arrayOf(IOException::class, WstxException::class)
    ) {
        hprService.hentPersonMedPersonnummer(doctorIdent, datatypeFactory.newXMLGregorianCalendar(GregorianCalendar()))
    }
}

fun createPdfPayload(
    legeerklaring: Legeerklaring,
    plan: PlanUtredBehandle,
    forslagTiltak: ForslagTiltak,
    typeLegeerklaering: Int,
    funksjonsevne: VurderingFunksjonsevne,
    prognose: no.nav.helse.legeerklaering.Prognose,
    healthcareProfessional: XMLHealthcareProfessional?,
    fellesformat: XMLEIFellesformat,
    validationResult: ValidationResult
): PdfPayload = PdfPayload(
    arbeidsvurderingVedSykefravaer = typeLegeerklaering == LegeerklaeringType.Arbeidsevnevurdering.type,
    arbeidsavklaringsPenger = typeLegeerklaering == LegeerklaeringType.Arbeidsavklaringspenger.type,
    yrkesrettetAttfoering = typeLegeerklaering == LegeerklaeringType.YrkesrettetAttfoering.type,
    ufoerepensjon = typeLegeerklaering == LegeerklaeringType.Ufoerepensjon.type,
    pasient = legeerklaeringToPasient(legeerklaring),
    sykdomsOpplysninger = mapLegeerklaeringToSykdomDiagnose(legeerklaring.diagnoseArbeidsuforhet),
    plan = Plan(
        utredning = plan?.henvistUtredning?.let {
            Henvisning(
                tekst = it.spesifikasjon,
                dato = it.henvistDato.toGregorianCalendar().toZonedDateTime(),
                antattVentetIUker = it.antattVentetid.toInt()
            )
        },
        behandling = plan?.henvistBehandling?.let {
            Henvisning(
                tekst = it.spesifikasjon,
                dato = it.henvistDato.toGregorianCalendar().toZonedDateTime(),
                antattVentetIUker = it.antattVentetid.toInt()
            )
        },
        utredningsplan = plan?.utredningsPlan,
        behandlingsplan = plan?.behandlingsPlan,
        vurderingAvTidligerePlan = plan?.nyVurdering,
        naarSpoerreOmNyeLegeopplysninger = plan?.nyeLegeopplysninger,
        videreBehandlingIkkeAktuellGrunn = plan?.ikkeVidereBehandling
    ),
    forslagTilTiltak = ForslagTilTiltak(
        behov = forslagTiltak.aktueltTiltak.isEmpty(),
        kjoepAvHelsetjenester = TypeTiltak.KjoepHelsetjenester in forslagTiltak.aktueltTiltak,
        reisetilskudd = TypeTiltak.Reisetilskudd in forslagTiltak.aktueltTiltak,
        aktivSykMelding = TypeTiltak.AktivSykemelding in forslagTiltak.aktueltTiltak,
        hjelpemidlerArbeidsplassen = TypeTiltak.HjelpemidlerArbeidsplass in forslagTiltak.aktueltTiltak,
        arbeidsavklaringsPenger = TypeTiltak.Arbeidsavklaringspenger in forslagTiltak.aktueltTiltak,
        friskemeldingTilArbeidsformidling = TypeTiltak.FriskemeldingTilArbeidsformidling in forslagTiltak.aktueltTiltak,
        andreTiltak = forslagTiltak.aktueltTiltak.find { it.typeTiltak == TypeTiltak.AndreTiltak }?.hvilkeAndreTiltak,
        naermereOpplysninger = forslagTiltak.opplysninger,
        tekst = forslagTiltak.begrensningerTiltak ?: forslagTiltak.begrunnelseIkkeTiltak
    ),
    funksjonsOgArbeidsevne = FunksjonsOgArbeidsevne(
        vurderingFunksjonsevne = funksjonsevne.funksjonsevne,
        iIntektsgivendeArbeid = ArbeidssituasjonType.InntektsgivendeArbeid in funksjonsevne.arbeidssituasjon,
        hjemmearbeidende = ArbeidssituasjonType.Hjemmearbeidende in funksjonsevne.arbeidssituasjon,
        student = ArbeidssituasjonType.Student in funksjonsevne.arbeidssituasjon,
        annetArbeid = funksjonsevne.arbeidssituasjon?.find {
            it.arbeidssituasjon?.let {
                it.toInt() == ArbeidssituasjonType.Annet?.type
            } ?: false
        }?.annenArbeidssituasjon ?: "",
        kravTilArbeid = funksjonsevne?.kravArbeid,
        kanGjenopptaTidligereArbeid = funksjonsevne.vurderingArbeidsevne?.gjenopptaArbeid?.toInt() == 1,
        kanGjenopptaTidligereArbeidNaa = funksjonsevne.vurderingArbeidsevne?.narGjenopptaArbeid?.toInt() == 1,
        kanGjenopptaTidligereArbeidEtterBehandling = funksjonsevne.vurderingArbeidsevne?.narGjenopptaArbeid?.toInt() == 2,
        kanTaAnnetArbeid = funksjonsevne.vurderingArbeidsevne?.taAnnetArbeid?.toInt() == 1,
        kanTaAnnetArbeidNaa = funksjonsevne.vurderingArbeidsevne?.narTaAnnetArbeid?.toInt() == 1,
        kanTaAnnetArbeidEtterBehandling = funksjonsevne.vurderingArbeidsevne?.narTaAnnetArbeid?.toInt() == 2,
        kanIkkeINaaverendeArbeid = funksjonsevne.vurderingArbeidsevne?.ikkeGjore,
        kanIkkeIAnnetArbeid = funksjonsevne.vurderingArbeidsevne?.hensynAnnetYrke
    ),
    prognose = Prognose(
        vilForbedreArbeidsevne = prognose.bedreArbeidsevne?.toInt() == 1,
        anslaatVarighetSykdom = prognose.antattVarighet,
        anslaatVarighetFunksjonsNedsetting = prognose.varighetFunksjonsnedsettelse,
        anslaatVarighetNedsattArbeidsevne = prognose.varighetNedsattArbeidsevne
    ),
    aarsaksSammenheng = legeerklaring.arsakssammenhengLegeerklaring,
    andreOpplysninger = legeerklaring.andreOpplysninger?.opplysning,
    kontakt = Kontakt(
        skalKontakteBehandlendeLege = KontaktType.BehandlendeLege in legeerklaring.kontakt,
        skalKontakteArbeidsgiver = KontaktType.Arbeidsgiver in legeerklaring.kontakt,
        skalKontakteBasisgruppe = KontaktType.Basisgruppe in legeerklaring.kontakt,
        kontakteAnnenInstans = legeerklaring.kontakt.find { it.kontakt?.toInt() == KontaktType.AnnenInstans.type }?.annenInstans,
        oenskesKopiAvVedtak = legeerklaring.andreOpplysninger?.onskesKopi?.let { it.toInt() == 1 } ?: false
    ),
    pasientenBurdeIkkeVite = legeerklaring.forbeholdLegeerklaring.borTilbakeholdes,
    signatur = Signatur(
        dato = ZonedDateTime.now(),
        navn = healthcareProfessional?.formatName() ?: "",
        adresse = fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation.address?.streetAdr,
        postnummer = fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation.address?.postalCode,
        poststed = fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation.address?.city,
        signatur = "",
        tlfNummer = healthcareProfessional?.teleCom?.firstOrNull()?.teleAddress?.v ?: ""
    ),
    validationResult = validationResult
)

fun mapEnkeltDiagnoseToDiagnose(enkeltdiagnose: Enkeltdiagnose?): Diagnose =
    Diagnose(tekst = enkeltdiagnose?.diagnose, kode = enkeltdiagnose?.kodeverdi)

fun mapLegeerklaeringToSykdomDiagnose(diagnose: DiagnoseArbeidsuforhet): SykdomsOpplysninger = SykdomsOpplysninger(
    hoveddiagnose = mapEnkeltDiagnoseToDiagnose(diagnose.diagnoseKodesystem.enkeltdiagnose.first()),
    bidiagnose = diagnose.diagnoseKodesystem.enkeltdiagnose.drop(1).map { mapEnkeltDiagnoseToDiagnose(it) },
    arbeidsufoerFra = diagnose.arbeidsuforFra?.toGregorianCalendar()?.toZonedDateTime(),
    sykdomsHistorie = diagnose.symptomerBehandling,
    statusPresens = diagnose.statusPresens,
    boerNavKontoretVurdereOmDetErEnYrkesskade = diagnose.vurderingYrkesskade?.borVurderes?.toInt() == 1
)

fun legeerklaeringToPasient(legeerklaering: Legeerklaring): Pasient {
    val patient = legeerklaering.pasientopplysninger.pasient
    val postalAddress = patient.arbeidsforhold?.virksomhet?.virksomhetsAdr?.postalAddress?.firstOrNull()
    return Pasient(
        fornavn = patient.navn.fornavn,
        mellomnavn = patient.navn.mellomnavn,
        etternavn = patient.navn.etternavn,
        foedselsnummer = patient.fodselsnummer,
        navKontor = patient.trygdekontor,
        adresse = patient.personAdr[0].postalAddress[0].streetAddress,
        postnummer = patient.personAdr[0].postalAddress[0].postalCode.let {
            if (it == null || it.isEmpty()) null else it.toInt()
        },
        poststed = patient.personAdr[0].postalAddress[0].city,
        yrke = patient.arbeidsforhold?.yrkesbetegnelse,
        arbeidsgiver = Arbeidsgiver(
            navn = patient.arbeidsforhold?.virksomhet?.virksomhetsBetegnelse,
            adresse = postalAddress?.streetAddress,
            postnummer = postalAddress?.postalCode.let {
                if (it == null || it.isEmpty()) null else it.toInt()
            },
            poststed = postalAddress?.city
        )
    )
}

enum class TypeTiltak(val typeTiltak: Int) {
    KjoepHelsetjenester(1),
    Reisetilskudd(2),
    AktivSykemelding(3),
    HjelpemidlerArbeidsplass(4),
    Arbeidsavklaringspenger(5),
    FriskemeldingTilArbeidsformidling(6),
    AndreTiltak(7)
}

operator fun Iterable<AktueltTiltak>.contains(typeTiltak: TypeTiltak) =
    any { it.typeTiltak.toInt() == typeTiltak.typeTiltak }

enum class LegeerklaeringType(val type: Int) {
    Arbeidsevnevurdering(1),
    Arbeidsavklaringspenger(2),
    YrkesrettetAttfoering(3),
    Ufoerepensjon(4)
}

enum class ArbeidssituasjonType(val type: Int) {
    InntektsgivendeArbeid(1),
    Hjemmearbeidende(2),
    Student(3),
    Annet(4)
}

operator fun Iterable<Arbeidssituasjon>.contains(arbeidssituasjonType: ArbeidssituasjonType): Boolean =
    any {
        it.arbeidssituasjon?.let {
            it.toInt() == arbeidssituasjonType.type
        } ?: false
    }

enum class KontaktType(val type: Int) {
    BehandlendeLege(1),
    Arbeidsgiver(2),
    Basisgruppe(4),
    AnnenInstans(5)
}

operator fun Iterable<no.nav.helse.legeerklaering.Kontakt>.contains(kontaktType: KontaktType): Boolean =
    any { it.kontakt.toInt() == kontaktType.type }

fun XMLHealthcareProfessional.formatName(): String = if (middleName == null) {
    "${familyName.toUpperCase()} ${givenName.toUpperCase()}"
} else {
    "${familyName.toUpperCase()} ${givenName.toUpperCase()} ${middleName.toUpperCase()}"
}

fun validationResult(results: List<Rule<Any>>): ValidationResult =
    ValidationResult(
        status = results
            .map { status -> status.status }.let {
                it.firstOrNull { status -> status == Status.MANUAL_PROCESSING }
                    ?: Status.OK
            },
        ruleHits = results.map { rule -> RuleInfo(rule.name, rule.messageForUser!!, rule.messageForSender!!) }
    )

suspend fun fetchGeografiskTilknytningAsync(
    personV3: PersonV3,
    personFNR: String
): HentGeografiskTilknytningResponse =
    retry(
        callName = "tps_hent_geografisktilknytning",
        retryIntervals = arrayOf(500L, 1000L, 3000L, 5000L, 10000L),
        legalExceptions = *arrayOf(IOException::class, WstxException::class, IllegalStateException::class)
    ) {
        personV3.hentGeografiskTilknytning(
            HentGeografiskTilknytningRequest().withAktoer(
                PersonIdent().withIdent(
                    NorskIdent()
                        .withIdent(personFNR)
                        .withType(Personidenter().withValue("FNR"))
                )
            )
        )
    }

suspend fun fetchBehandlendeEnhet(
    arbeidsfordelingV1: ArbeidsfordelingV1,
    geografiskTilknytning: GeografiskTilknytning?,
    patientDiskresjonsKode: String?
): FinnBehandlendeEnhetListeResponse? =
    retry(
        callName = "finn_nav_kontor",
        retryIntervals = arrayOf(500L, 1000L, 3000L, 5000L, 10000L),
        legalExceptions = *arrayOf(IOException::class, WstxException::class)
    ) {
        arbeidsfordelingV1.finnBehandlendeEnhetListe(FinnBehandlendeEnhetListeRequest().apply {
            val afk = ArbeidsfordelingKriterier()
            if (geografiskTilknytning?.geografiskTilknytning != null) {
                afk.geografiskTilknytning = Geografi().apply {
                    value = geografiskTilknytning.geografiskTilknytning
                }
            }
            afk.tema = Tema().apply {
                value = "SYM"
            }

            afk.oppgavetype = Oppgavetyper().apply {
                value = "BEH_EL_SYM"
            }

            if (!patientDiskresjonsKode.isNullOrBlank()) {
                afk.diskresjonskode = Diskresjonskoder().apply {
                    value = patientDiskresjonsKode
                }
            }

            arbeidsfordelingKriterier = afk
        })
    }

/*
fun sendArenaInfo(
    producer: MessageProducer,
    session: Session,
    fellesformat: XMLEIFellesformat,
    validationResult: ValidationResult
    tssid: String?
    navkontorNr: Sting
) = producer.send(session.createTextMessage().apply {
    val info = createArenaInfo(fellesformat, tssid, sperrekode, navkontor).apply {
        // TODO eiaData may not be used by Arena
        eiaData = ArenaEiaInfo.EiaData().apply {
            systemSvar.addAll(validationResult.outcomes
                .map { it.toSystemSvar() })

            if (systemSvar.isEmpty()) {
                systemSvar.add(OutcomeType.LEGEERKLAERING_MOTTAT.toOutcome().toSystemSvar())
            }
        }
    }
    text = arenaMarshaller.toString(info)
}) */
