package com.tempobox.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tag fields a smart playlist can match on. Declaration order is the builder
 * dropdown's order; storage serializes by name, so reordering is safe.
 */
@Serializable
enum class RuleField {
    TITLE,
    ARTIST,
    ALBUM_ARTIST,
    ALBUM,
    GENRE,
    YEAR,
    RATING,
    TRACK_NUMBER,
    DISC_NUMBER;

    /** Numeric fields support ordering operators; the rest are text matches. */
    val isNumeric: Boolean get() = when (this) {
        YEAR, RATING, TRACK_NUMBER, DISC_NUMBER -> true
        TITLE, ARTIST, ALBUM_ARTIST, ALBUM, GENRE -> false
    }

    /** Human label, the single source for the builder and rule summaries. */
    val label: String get() = when (this) {
        TITLE -> "Title"
        ARTIST -> "Artist"
        ALBUM_ARTIST -> "Album artist"
        ALBUM -> "Album"
        GENRE -> "Genre"
        YEAR -> "Year"
        RATING -> "Rating"
        TRACK_NUMBER -> "Track number"
        DISC_NUMBER -> "Disc number"
    }
}

/** Comparison operators. LESS/GREATER are only valid for numeric fields. */
@Serializable
enum class RuleOp {
    IS,
    IS_NOT,
    CONTAINS,
    LESS_THAN,
    GREATER_THAN;

    /** Human label, the single source for the builder and rule summaries. */
    val label: String get() = when (this) {
        IS -> "is"
        IS_NOT -> "is not"
        CONTAINS -> "contains"
        LESS_THAN -> "<"
        GREATER_THAN -> ">"
    }

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
    ) : SmartRule {
        /**
         * True when [value] can actually be evaluated for [field]: non-blank,
         * and parseable for numeric fields. The builder gates Create on it.
         */
        val isComplete: Boolean
            // this.field: inside a getter the bare name is the backing field.
            get() = value.isNotBlank() &&
                (!this.field.isNumeric || value.trim().toIntOrNull() != null)
    }

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

// Exhaustive on purpose: a future field added here without a branch would
// otherwise compile and silently never match.
private fun SmartRule.Condition.matchesCondition(track: Track): Boolean = when (field) {
    RuleField.TITLE -> matchesText(track.title)
    RuleField.ARTIST -> matchesText(track.artist)
    RuleField.ALBUM_ARTIST -> matchesText(track.effectiveAlbumArtist)
    // Effective values, so untagged files match their placeholder
    // ("Unknown Album"), same as the library views group them.
    RuleField.ALBUM -> matchesText(track.effectiveAlbum)
    RuleField.GENRE -> matchesText(track.effectiveGenre)
    RuleField.YEAR -> matchesNumber(track.year)
    RuleField.RATING -> matchesNumber(track.rating)
    RuleField.TRACK_NUMBER -> matchesNumber(track.trackNumber)
    RuleField.DISC_NUMBER -> matchesNumber(track.discNumber)
}

private fun SmartRule.Condition.matchesText(actual: String): Boolean = when (op) {
    RuleOp.IS -> actual.equals(value.trim(), ignoreCase = true)
    RuleOp.IS_NOT -> !actual.equals(value.trim(), ignoreCase = true)
    RuleOp.CONTAINS -> actual.contains(value.trim(), ignoreCase = true)
    RuleOp.LESS_THAN, RuleOp.GREATER_THAN -> false // not defined for strings
}

private fun SmartRule.Condition.matchesNumber(actual: Int?): Boolean {
    val target = value.trim().toIntOrNull() ?: return false
    // An absent tag can never satisfy an ordered comparison, but it does
    // differ from every value, so IS_NOT still matches.
    if (actual == null) return op == RuleOp.IS_NOT
    return when (op) {
        RuleOp.IS -> actual == target
        RuleOp.IS_NOT -> actual != target
        RuleOp.LESS_THAN -> actual < target
        RuleOp.GREATER_THAN -> actual > target
        RuleOp.CONTAINS -> false // not defined for numeric fields
    }
}
