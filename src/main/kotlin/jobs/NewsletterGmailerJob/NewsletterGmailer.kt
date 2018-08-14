package jobs.NewsletterGmailerJob

import com.github.jknack.handlebars.Handlebars
import com.google.api.services.gmail.model.Message
import config.Configuration
import config.Configurator
import config.RequiredConfig
import config.RequiredConfigItem
import config.stringToDayOfWeek
import datastore.ApplicationState
import datastore.DropboxDatastore
import datastore.DropboxWriteFailure
import datastore.ErrorDownloadingFileFromDropbox
import datastore.FlatFileApplicationStateMetadata
import datastore.HttpDropboxClient
import gmail.AuthorisedGmailProvider
import gmail.Email
import gmail.GmailSecrets
import gmail.HttpGmailClient
import gmail.MessageString
import gmail.SimpleGmailClient
import gmail.decodeRawAsStringWithoutMessageId
import jobs.Job
import jobs.JobCompanion
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_BCC_ADDRESS
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_BODY_A
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_BODY_B
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_DROPBOX_ACCESS_TOKEN
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_FROM_ADDRESS
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_FROM_FULLNAME
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_GMAIL_ACCESS_TOKEN
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_GMAIL_CLIENT_SECRET
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_GMAIL_REFRESH_TOKEN
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_JOB_NAME
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_RUN_AFTER_TIME
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_RUN_ON_DAYS
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_SUBJECT_A
import jobs.NewsletterGmailerJob.NewsletterGmailer.Companion.NewsletterGmailerConfigItem.NEWSLETTER_GMAILER_SUBJECT_B
import jobs.NewsletterGmailerJob.NewsletterGmailer.Members
import jobs.NewsletterGmailerJob.NewsletterGmailer.NewsletterGmailerStatus
import jobs.NewsletterGmailerJob.NewsletterGmailer.NewsletterGmailerStatus.CLEANING_THIS_WEEK
import jobs.NewsletterGmailerJob.NewsletterGmailer.NewsletterGmailerStatus.NOT_CLEANING_THIS_WEEK
import jobs.NewsletterGmailerJob.TemplatedMessage.CompiledTemplate
import jobs.NewsletterGmailerJob.TemplatedMessage.RawTemplate
import result.AnEmailAlreadySentToday
import result.CouldNotSendEmail
import result.Err
import result.NoNeedToRun
import result.NoNeedToRunAtThisTime
import result.NoNeedToRunOnThisDayOfWeek
import result.NotAListOfEmailAddresses
import result.Result
import result.Result.Failure
import result.Result.Success
import result.ThisEmailAlreadySent
import result.asSuccess
import result.flatMap
import result.map
import result.orElse
import java.nio.file.Paths
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress

class NewsletterGmailer(private val gmailClient: SimpleGmailClient, private val appStateDatastore: DropboxDatastore<NewsletterGmailerState>, private val membersDatastore: DropboxDatastore<Members>, private val config: Configuration): Job {
    override val jobName: String = config.get(NEWSLETTER_GMAILER_JOB_NAME)

    companion object: JobCompanion {

        sealed class NewsletterGmailerConfigItem : RequiredConfigItem {
            object NEWSLETTER_GMAILER_JOB_NAME : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_GMAIL_CLIENT_SECRET : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_GMAIL_ACCESS_TOKEN : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_GMAIL_REFRESH_TOKEN : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_DROPBOX_ACCESS_TOKEN : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_RUN_ON_DAYS : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_RUN_AFTER_TIME : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_FROM_ADDRESS : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_FROM_FULLNAME : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_BCC_ADDRESS : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_SUBJECT_A : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_SUBJECT_B : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_BODY_A : NewsletterGmailerConfigItem()
            object NEWSLETTER_GMAILER_BODY_B : NewsletterGmailerConfigItem()
        }

        class NewsletterGmailerConfig: RequiredConfig {
            override fun values() = setOf(
                    NEWSLETTER_GMAILER_JOB_NAME,
                    NEWSLETTER_GMAILER_GMAIL_CLIENT_SECRET,
                    NEWSLETTER_GMAILER_GMAIL_ACCESS_TOKEN,
                    NEWSLETTER_GMAILER_GMAIL_REFRESH_TOKEN,
                    NEWSLETTER_GMAILER_DROPBOX_ACCESS_TOKEN,
                    NEWSLETTER_GMAILER_RUN_ON_DAYS,
                    NEWSLETTER_GMAILER_RUN_AFTER_TIME,
                    NEWSLETTER_GMAILER_FROM_ADDRESS,
                    NEWSLETTER_GMAILER_FROM_FULLNAME,
                    NEWSLETTER_GMAILER_BCC_ADDRESS,
                    NEWSLETTER_GMAILER_SUBJECT_A,
                    NEWSLETTER_GMAILER_SUBJECT_B,
                    NEWSLETTER_GMAILER_BODY_A,
                    NEWSLETTER_GMAILER_BODY_B
            )
        }

        override fun initialise(): NewsletterGmailer {
            val config = Configurator(NewsletterGmailerConfig(), Paths.get("credentials"))
            val gmailSecrets = GmailSecrets(
                    config.get(NEWSLETTER_GMAILER_GMAIL_CLIENT_SECRET),
                    config.get(NEWSLETTER_GMAILER_GMAIL_ACCESS_TOKEN),
                    config.get(NEWSLETTER_GMAILER_GMAIL_REFRESH_TOKEN)
            )
            val gmail = AuthorisedGmailProvider(4000, config.get(NEWSLETTER_GMAILER_JOB_NAME), gmailSecrets, config).gmail()
            val gmailClient = HttpGmailClient(gmail)
            val dropboxClient = HttpDropboxClient(config.get(NEWSLETTER_GMAILER_JOB_NAME), config.get(NEWSLETTER_GMAILER_DROPBOX_ACCESS_TOKEN))
            val appStateMetadata = FlatFileApplicationStateMetadata("/newsletter_gmailer.json", NewsletterGmailerState::class.java)
            val appStateDatastore = DropboxDatastore(dropboxClient, appStateMetadata)
            val membersMetadata = FlatFileApplicationStateMetadata("/members2.json", Members::class.java)
            val membersDatastore = DropboxDatastore(dropboxClient, membersMetadata)
            return NewsletterGmailer(gmailClient, appStateDatastore, membersDatastore, config)
        }
    }

    override fun run(now: ZonedDateTime): String =
        shouldRunFor(now).flatMap { ExternalStateRetriever(appStateDatastore, membersDatastore).retrieve() }
                      .flatMap { externalState -> externalState.checkNoEmailSentToday(now.toLocalDate()) }
                      .flatMap { externalState ->
                          when (externalState.appState.status) {
                              CLEANING_THIS_WEEK     -> cleaningContext(externalState)
                              NOT_CLEANING_THIS_WEEK -> notCleaningContext(externalState)
                          }
                      }
                      .flatMap { context -> context.validateNotADuplicate() }
                      .flatMap { context -> context.sendAsGmailMessage() }
                      .flatMap { context -> updateAppStateInDb(
                                                context.toGmailMessage(),
                                                context.appState,
                                                context.members.nextMemberAfter(context.cleanerOnNotice),
                                                context.successMessage,
                                                now) }
                      .orElse { error: Err -> error.message }

    private fun shouldRunFor(now: ZonedDateTime): Result<NoNeedToRun, ZonedDateTime> {
        val daysToRun: List<DayOfWeek> = config.getAsListOf(NEWSLETTER_GMAILER_RUN_ON_DAYS, stringToDayOfWeek)
        val timeToRunAfter = LocalTime.parse(config.get(NEWSLETTER_GMAILER_RUN_AFTER_TIME), DateTimeFormatter.ofPattern("HH:mm"))
        val dayOfWeek = now.dayOfWeek
        val time = now.toLocalTime()
        return when {
            daysToRun.contains(dayOfWeek).not()  -> Failure(NoNeedToRunOnThisDayOfWeek(dayOfWeek, daysToRun))
            time < timeToRunAfter                -> Failure(NoNeedToRunAtThisTime(time, timeToRunAfter))
            else                                 -> Success(now)
        }
    }

    private fun ExternalState.checkNoEmailSentToday(now: LocalDate): Result<AnEmailAlreadySentToday, ExternalState> {
        return when {
            now == appState.lastRanOn -> Failure(AnEmailAlreadySentToday())
            else                      -> this.asSuccess()
        }
    }

    private fun cleaningContext(externalState: ExternalState): Success<Context> {
        val cleanerOnNotice = externalState.appState.cleaner!!
        return Context(
                externalState.appState,
                externalState.members,
                externalState.members.allInternetAddresses(),
                CompiledTemplate.from(RawTemplate(config.get(NEWSLETTER_GMAILER_SUBJECT_A)), mapOf("cleaner" to cleanerOnNotice.fullname())),
                config.get(NEWSLETTER_GMAILER_BODY_A),
                RawTemplate("{{cleaner}} is cleaning this week - an email has been sent to all members."),
                cleanerOnNotice
        ).asSuccess()
    }

    private fun notCleaningContext(externalState: ExternalState): Success<Context> {
        val cleanerOnNotice = externalState.appState.nextUp
        return Context(
                externalState.appState,
                externalState.members,
                listOf(externalState.appState.nextUp.internetAddress()),
                CompiledTemplate.from(RawTemplate(config.get(NEWSLETTER_GMAILER_SUBJECT_B)), mapOf("cleaner" to cleanerOnNotice.fullname())),
                config.get(NEWSLETTER_GMAILER_BODY_B),
                RawTemplate("There is no cleaning this week - an email reminder has been sent to {{cleaner}} who is cleaning next week."),
                cleanerOnNotice
        ).asSuccess()
    }

    private fun Context.validateNotADuplicate(): Result<ThisEmailAlreadySent, Context> =
        if (thisMessageWasAlreadySent(this.toGmailMessage(), appState.emailContents)) {
            Failure(ThisEmailAlreadySent())
        } else {
            Success(this)
        }

    private fun Context.sendAsGmailMessage(): Result<CouldNotSendEmail, Context> =
        gmailClient.send(this.toGmailMessage(), this.emailSubject.value, this.recipients)
                .map { this.copy(successMessage = CompiledTemplate.from(successMessage, mapOf("cleaner" to cleanerOnNotice.fullname())))}

    private fun updateAppStateInDb(message: Message, appState: NewsletterGmailerState, cleanerOnNotice: Member, successMessage: TemplatedMessage, now: ZonedDateTime): Result<DropboxWriteFailure, String> {
        val nextStatus: NewsletterGmailerStatus = appState.status.flip()
        val cleaner = if (nextStatus == CLEANING_THIS_WEEK) appState.nextUp else null
        val newEmailContents = gmailClient.newMessageFrom(message.decodeRaw()).content.toString()
        val newState = NewsletterGmailerState(nextStatus, cleaner, cleanerOnNotice, now.toLocalDate(), newEmailContents)
        return appStateDatastore.store(newState, successMessage.value)
    }

    private fun thisMessageWasAlreadySent(message: Message, previousEmailContents: String) =
            message.decodeRawAsStringWithoutMessageId() == MessageString(previousEmailContents).withoutMessageIdAsString()

    private fun Context.toGmailMessage() : Message {
        val from = InternetAddress(
                config.get(NEWSLETTER_GMAILER_FROM_ADDRESS),
                config.get(NEWSLETTER_GMAILER_FROM_FULLNAME)
        )
        val to = recipients
        val bccResult = config.get(NEWSLETTER_GMAILER_BCC_ADDRESS).toInternetAddresses()
        val bcc = (bccResult as Success).value
        val subject = emailSubject.value
        val body = emailBody
        val email = Email(from, to, bcc, subject, body)
        return email.toGmailMessage()
    }

    private fun NewsletterGmailerStatus.flip() = when (this) {
        CLEANING_THIS_WEEK     -> NOT_CLEANING_THIS_WEEK
        NOT_CLEANING_THIS_WEEK -> CLEANING_THIS_WEEK
    }

    private fun String.toInternetAddresses(delimiter: Char = ','): Result<NotAListOfEmailAddresses, List<InternetAddress>> =
        try {
            Success(this.split(delimiter).map { InternetAddress(it, true) })
        } catch (e: Exception) {
            when (e) {
                is AddressException -> Failure(NotAListOfEmailAddresses(this))
                else                -> throw e
            }
        }

    enum class NewsletterGmailerStatus {
        CLEANING_THIS_WEEK,
        NOT_CLEANING_THIS_WEEK
    }

    data class Members(val members: List<Member>): ApplicationState {
        fun allInternetAddresses(): List<InternetAddress> = members.map { it.internetAddress() }
        fun nextMemberAfter(member: Member): Member {
            val membersIterator = members.listIterator(members.indexOf(member) + 1)
            return when {
                membersIterator.hasNext() -> membersIterator.next()
                else                      -> members.first()
            }
        }
    }

    data class Context(
            val appState: NewsletterGmailerState,
            val members: Members,
            val recipients: List<InternetAddress>,
            val emailSubject: TemplatedMessage,
            val emailBody: String,
            val successMessage: TemplatedMessage,
            val cleanerOnNotice: Member
    )

    class ExternalStateRetriever(private val appStateDatastore: DropboxDatastore<NewsletterGmailerState>, private val membersDatastore: DropboxDatastore<Members>) {
        fun retrieve(): Result<ErrorDownloadingFileFromDropbox, ExternalState> {
            val currentApplicationState = appStateDatastore.currentApplicationState()
            val currentMembers = membersDatastore.currentApplicationState()

            return when {
                currentApplicationState is Failure -> Failure(currentApplicationState.reason)
                currentMembers is Failure          -> Failure(currentMembers.reason)
                else                               -> Success(ExternalState(
                        (currentApplicationState as Success).value,
                        (currentMembers as Success).value
                ))
            }
        }
    }
}

data class NewsletterGmailerState(
        val status: NewsletterGmailerStatus,
        val cleaner: Member?,
        val nextUp: Member,
        val lastRanOn: LocalDate,
        val emailContents: String
) : ApplicationState

data class Member(val name: String, val surname: String?, val email: String) {

    fun internetAddress(): InternetAddress =
            InternetAddress(email, fullname())
    fun fullname(): String = "$name${surname?.let { " $it" } ?: ""}"
}

data class ExternalState(val appState: NewsletterGmailerState, val members: Members)

sealed class TemplatedMessage(val value: String) {
    class RawTemplate(message: String): TemplatedMessage(message)
    class CompiledTemplate private constructor(message: String): TemplatedMessage(message) {
        companion object {
            fun from(templatedMessage: TemplatedMessage, model: Map<String, String>): CompiledTemplate =
                when (templatedMessage) {
                    is RawTemplate      -> CompiledTemplate(Handlebars().compileInline(templatedMessage.value).apply(model))
                    is CompiledTemplate -> templatedMessage
                }
        }
    }
}
