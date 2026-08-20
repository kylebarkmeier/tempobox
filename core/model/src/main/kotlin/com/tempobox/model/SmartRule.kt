package com.tempobox.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Tag fields a smart playlist can match on. */
@Serializable
enum class RuleField {
    ALBUM_ARTIST,
    ARTIST,
    GENRE,
    YEAR,
    RATING;

    /** YEAR and RATING support ordering operators; the rest are equality-only. */
    val isNumeric: Boolean get() = this == YEAR || this == RATING
}

/** Comparison operators. LESS/GREATER are only valid for numeric fields. */
@Serializable
enum class RuleOp {
    IS,
    IS_NOT,
    CONTAINS,
    LESS_THAN,
    GREATER_THAN;

    companion object {
        fun operatorsFor(field: RuleField): List<RuleOp> =
            if (field.isNumeric) listOf(IS, IS_NOT, LESS_THAN, GREATER_THAN)
            else listOf(IS, IS_NOT, CONTAINS)
    }
}

/**
 * A smart-playlist rule tree: either a single [Condition] or a boolean
 * combination ([AllOf] = AND, [AnyOf] = OR) of sub-rules. Trees nest to any
 * depth, so "(Genre is Rock OR Genre is Metal) AND Year > 1990 AND Rating > 3"
 * is representable.
 *
 * Serialized to JSON (kotlinx-serialization, polymorphic by `kind`) for storage
 * in the database.
 */
@Serializable
sealed interface SmartRule {

    /** Leaf condition comparing one field against a value. */
    @Serializable
    @SerialName("condition")
    data class Condition(
        val field: RuleField,
        val op: RuleOp,
        /** String fields compare case-insensitively; numeric fields parse this as Int. */
        val value: String,
    ) : SmartRule

    /** True when every sub-rule matches (boolean AND). */
    @Serializable
    @SerialName("all")
    data class AllOf(val rules: List<SmartRule>) : SmartRule

    /** True when at least one sub-rule matches (boolean OR). */
    @Serializable
    @SerialName("any")
    data class AnyOf(val rules: List<SmartRule>) : SmartRule

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "kind"
        }

        fun toJson(rule: SmartRule): String = json.encodeToString(serializer(), rule)

        /** Returns null instead of throwing on malformed/legacy JSON. */
        fun fromJson(raw: String): SmartRule? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
    }
}

/**
 * Evaluates a rule tree against a track. Pure function — the single source of
 * truth for smart-playlist membership, exercised heavily by unit tests.
 */
fun SmartRule.matches(track: Track): Boolean = when (this) {
    is SmartRule.AllOf -> rules.all { it.matches(track) }
    is SmartRule.AnyOf -> rules.any { it.matches(track) }
    is SmartRule.Condition -> matchesCondition(track)
}

private fun SmartRule.Condition.matchesCondition(track: Track): Boolean {
    return if (field.isNumeric) {
        val target = value.trim().toIntOrNull() ?: return false
        val actual = when (field) {
            RuleField.YEAR -> track.year ?: return op == RuleOp.IS_NOT
            RuleField.RATING -> track.rating
            else -> return false
        }
        when (op) {
            RuleOp.IS -> actual == target
            RuleOp.IS_NOT -> actual != target
            RuleOp.LESS_THAN -> actual < target
            RuleOp.GREATER_THAN -> actual > target
            RuleOp.CONTAINS -> false // not defined for numeric fields
        }
    } else {
        val actual = when (field) {
            RuleField.ALBUM_ARTIST -> track.effectiveAlbumArtist
            RuleField.ARTIST -> track.artist
            RuleField.GENRE -> track.effectiveGenre
            else -> return false
        }
        when (op) {
            RuleOp.IS -> actual.equals(value.trim(), ignoreCase = true)
            RuleOp.IS_NOT -> !actual.equals(value.trim(), ignoreCase = true)
            RuleOp.CONTAINS -> actual.contains(value.trim(), ignoreCase = true)
            RuleOp.LESS_THAN, RuleOp.GREATER_THAN -> false // not defined for strings
        }
    }
}
