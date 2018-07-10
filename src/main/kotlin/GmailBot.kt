import GmailBot.Companion.RequiredConfig
import GmailBot.Companion.RequiredConfig.KOTLIN_GMAILER_BCC_ADDRESS
import GmailBot.Companion.RequiredConfig.KOTLIN_GMAILER_FROM_ADDRESS
import GmailBot.Companion.RequiredConfig.KOTLIN_GMAILER_FROM_FULLNAME
import GmailBot.Companion.RequiredConfig.KOTLIN_GMAILER_GMAIL_QUERY
import GmailBot.Companion.RequiredConfig.KOTLIN_GMAILER_RUN_ON_DAYS
import GmailBot.Companion.RequiredConfig.KOTLIN_GMAILER_TO_ADDRESS
import GmailBot.Companion.RequiredConfig.KOTLIN_GMAILER_TO_FULLNAME
import Result.Failure
import Result.Success
import com.google.api.services.gmail.model.Message
import config.Configuration
import config.Configurator
import datastore.Datastore
import datastore.DropboxDatastore
import datastore.FlatFileApplicationStateMetadata
import datastore.HttpSimpleDropboxClient
import datastore.SimpleDropboxClient
import datastore.WriteState.WriteFailure
import datastore.WriteState.WriteSuccess
import gmail.AuthorisedGmailProvider
import gmail.Gmailer
import gmail.GmailerState
import gmail.HttpGmailer
import gmail.encode
import gmail.replaceRecipient
import gmail.replaceSender
import java.nio.file.Paths
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import javax.mail.Message.RecipientType
import javax.mail.internet.InternetAddress

fun main(args: Array<String>) {
    val requiredConfig: List<RequiredConfig> = RequiredConfig.values().toList()
    val config = Configurator(requiredConfig, Paths.get("credentials"))
    val gmail = AuthorisedGmailProvider(4000, GmailBot.appName, config).gmail()
    val gmailer = HttpGmailer(gmail)
    val dropboxClient = HttpSimpleDropboxClient(GmailBot.appName, config)
    val runOnDays = config.get(KOTLIN_GMAILER_RUN_ON_DAYS).split(",").map { it.trim().toInt() }
    val result = GmailBot(gmailer, dropboxClient, config).run(ZonedDateTime.now(), runOnDays)
    println(result)
}

class GmailBot(private val gmailer: Gmailer, private val dropboxClient: SimpleDropboxClient, private val config: Configuration) {

        companion object {
            const val appName = "kotlin-gmailer-bot"

            enum class RequiredConfig {
                KOTLIN_GMAILER_GMAIL_CLIENT_SECRET,
                KOTLIN_GMAILER_GMAIL_ACCESS_TOKEN,
                KOTLIN_GMAILER_GMAIL_REFRESH_TOKEN,
                KOTLIN_GMAILER_DROPBOX_ACCESS_TOKEN,
                KOTLIN_GMAILER_GMAIL_QUERY,
                KOTLIN_GMAILER_RUN_ON_DAYS,
                KOTLIN_GMAILER_FROM_ADDRESS,
                KOTLIN_GMAILER_FROM_FULLNAME,
                KOTLIN_GMAILER_TO_ADDRESS,
                KOTLIN_GMAILER_TO_FULLNAME,
                KOTLIN_GMAILER_BCC_ADDRESS
            }
        }

    fun run(now: ZonedDateTime, daysOfMonthToRun: List<Int>): String {

        fun List<Int>.includes(dayOfMonth: Int): Result<NoNeedToRunAtThisTime, ZonedDateTime> = when {
            this.contains(dayOfMonth) -> Success(now)
            else                      -> Failure(NoNeedToRunAtThisTime(dayOfMonth, daysOfMonthToRun))
        }

        val shouldRunNow = daysOfMonthToRun.includes(now.dayOfMonth)
        if (shouldRunNow is Failure) return shouldRunNow.reason.message

        val appStateMetadata = FlatFileApplicationStateMetadata("/gmailer_state.json", GmailerState::class.java)
        val datastore: Datastore<GmailerState> = DropboxDatastore(dropboxClient, appStateMetadata)
        val appStateResult = datastore.currentApplicationState()
        val applicationState = when (appStateResult) {
            is Success -> appStateResult.value
            is Failure -> return appStateResult.reason.message
        }

        val gmailQuery = config.get(KOTLIN_GMAILER_GMAIL_QUERY)
        val searchResult: Message? = gmailer.lastEmailForQuery(gmailQuery)
        val emailBytes = searchResult?.let {
             gmailer.rawContentOf(searchResult)
        }

        val lastEmailSent = applicationState.lastEmailSent
        val state = when {
            lastEmailSent > now                                                            -> State.INVALID_STATE_IN_FUTURE
            emailBytes != null && thisExactEmailAlreadySent(emailBytes, applicationState)  -> State.THIS_EMAIL_ALREADY_SENT
            lastEmailSent.yearMonth() == now.yearMonth()                                   -> State.AN_EMAIL_ALREADY_SENT_THIS_MONTH
            lastEmailSent.yearMonth() < now.yearMonth()                                    -> State.NO_EMAIL_SENT_THIS_MONTH
            else                                                                           -> State.UNKNOWN_ERROR
        }

        return when (state) {
            State.THIS_EMAIL_ALREADY_SENT          -> "Exiting as this exact email has already been sent"
            State.AN_EMAIL_ALREADY_SENT_THIS_MONTH -> "Exiting, email has already been sent for ${now.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${now.year}"
            State.UNKNOWN_ERROR                    -> "Exiting due to unknown error"
            State.INVALID_STATE_IN_FUTURE          -> "Exiting due to invalid state, previous email appears to have been sent in the future"
            State.NO_EMAIL_SENT_THIS_MONTH         -> tryToSendEmail(datastore, emailBytes)
        }
    }

    private fun thisExactEmailAlreadySent(emailBytes: ByteArray, applicationState: GmailerState): Boolean {
        val separatorBetweenHeaderAndMainContent = "________________________________"
        val newEmailContents = String(emailBytes).substringAfter(separatorBetweenHeaderAndMainContent)
        val previousEmailContents = applicationState.emailContents.substringAfter(separatorBetweenHeaderAndMainContent)
        return newEmailContents.contentEquals(previousEmailContents)
    }

    private fun tryToSendEmail(datastore: Datastore<GmailerState>, rawMessageToSend: ByteArray?): String {
        val fromEmailAddress = config.get(KOTLIN_GMAILER_FROM_ADDRESS)
        val fromFullName = config.get(KOTLIN_GMAILER_FROM_FULLNAME)
        val toEmailAddress = config.get(KOTLIN_GMAILER_TO_ADDRESS)
        val toFullName = config.get(KOTLIN_GMAILER_TO_FULLNAME)
        val bccEmailAddress = config.get(KOTLIN_GMAILER_BCC_ADDRESS)

        rawMessageToSend?.let {
            val clonedMessage = gmailer.newMessageFrom(rawMessageToSend)
            val clonedMessageWithNewHeader = clonedMessage?.run {
                    replaceSender(InternetAddress(fromEmailAddress, fromFullName))
                    replaceRecipient(InternetAddress(toEmailAddress, toFullName), RecipientType.TO)
                    replaceRecipient(InternetAddress(bccEmailAddress), RecipientType.BCC)
                    encode()
            }

            val gmailResponse = clonedMessageWithNewHeader?.let { gmailer.send(clonedMessageWithNewHeader) }

            val dropboxState = gmailResponse?.let {
                val emailContents = clonedMessageWithNewHeader.decodeRaw()?.let { String(it) }
                val newState = emailContents?.let { GmailerState(ZonedDateTime.now(), emailContents) }
                newState?.let { datastore.store(newState) }
            }

            val wasEmailSent = gmailResponse?.let {
                "New email has been sent"
            } ?: "Error - could not send email/s"

            val wasStateUpdated = dropboxState?.let {
                when (it) {
                    is WriteSuccess -> "Current state has been stored in Dropbox"
                    is WriteFailure -> "Error - could not store state in Dropbox"
                }
            } ?: ""

            val resultMessages = listOf(wasEmailSent, wasStateUpdated).filter { it.isNotBlank() }
            return resultMessages.joinToString("\n")
        }

        return "Error - could not get raw message content for email"
    }

    private fun ZonedDateTime.yearMonth(): YearMonth = YearMonth.from(this)
}

enum class State {
    NO_EMAIL_SENT_THIS_MONTH,
    AN_EMAIL_ALREADY_SENT_THIS_MONTH,
    THIS_EMAIL_ALREADY_SENT,
    INVALID_STATE_IN_FUTURE,
    UNKNOWN_ERROR
}


sealed class Result<out E, out T> {
    data class Success<out T>(val value: T) : Result<Nothing, T>()
    data class Failure<out E>(val reason: E) : Result<E, Nothing>()
}

fun <E, T, U> Result<E, T>.map(f: (T) -> U): Result<E, U> =
        when (this) {
            is Success<T> -> Success(f(value))
            is Failure<E> -> this
        }

fun <E, T, U> Result<E, T>.flatMap(f: (T) -> Result<E, U>): Result<E, U> =
    when (this) {
        is Success<T> -> f(value)
        is Failure<E> -> this
    }

fun <E, F, T> Result<E, T>.fold(failure: (E) -> F, success: (T) -> F): F = this.map(success).orElse(failure)

fun <E, T> Result<E, T>.orElse(f: (E) -> T): T =
        when (this) {
            is Success<T> -> this.value
            is Failure<E> -> f(this.reason)
        }

class NoNeedToRunAtThisTime(dayOfMonth: Int, daysOfMonthToRun: List<Int>) {
    val message = "No need to run: day of month is: $dayOfMonth, only running on day ${daysOfMonthToRun.joinToString(", ")} of each month"
}
