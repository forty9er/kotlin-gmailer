package config

import jobs.GmailBot.Companion.RequiredConfig
import java.nio.file.Path

class Configuration (private val values: Map<RequiredConfig, String?>, val configDir: Path?) {

    init {
        validate(RequiredConfig.values().toSet(), values)
    }

    fun get(requiredConfig: RequiredConfig): String = try {
        this.values[requiredConfig]!!
    } catch (e: Exception) {
        throw ConfigurationException("$requiredConfig was not available during get")
    }

    fun getAsListOfInt(requiredConfig: RequiredConfig, delimiter: Char = ','): List<Int> = try {
        this.get(requiredConfig).split(delimiter).map { it.trim().toInt() }
    } catch (e: Exception) {
        throw ConfigurationException("It was not safe to return $requiredConfig as a list of ${Int::class}")
    }

    private fun validate(required: Set<RequiredConfig>, provided: Map<RequiredConfig, String?>) {
        fun pluralise(missingConfig: List<RequiredConfig>): String = when {
            missingConfig.size > 1 -> "s"
            else                   -> ""
        }

        if (required.sorted() != provided.keys.sorted() || provided.values.contains(null)) {
            val completelyMissing  = required.filter { provided.contains(it).not() }
            val nullValues = provided.filter { it.value == null }.keys
            val missingConfig = completelyMissing + nullValues
            throw ConfigurationException(
                    "Config value${pluralise(missingConfig)} required for " +
                            "${missingConfig.joinToString(", ") { it.name }} " +
                            "but not found."
            )
        }
    }
}

class ConfigurationException(override val message: String) : RuntimeException()