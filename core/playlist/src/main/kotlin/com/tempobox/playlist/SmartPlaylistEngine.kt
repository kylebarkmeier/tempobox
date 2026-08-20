package com.tempobox.playlist

import com.tempobox.model.SmartRule
import com.tempobox.model.Track
import com.tempobox.model.matches
import com.tempobox.model.sortNormalized
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Materializes smart ("auto") playlists: filters the library through a
 * [SmartRule] tree and produces a stable, human-sensible ordering.
 *
 * Kept free of database/coroutine concerns so membership logic is exercised
 * directly by fast unit tests; `core:library`'s repository feeds it the track
 * list and reacts to library changes (which keeps smart playlists up to date
 * with new additions automatically).
 */
@Singleton
class SmartPlaylistEngine @Inject constructor() {

    /**
     * Tracks matching [rule], ordered by album artist → album → disc → track →
     * title so the result plays like a sensible library slice.
     */
    fun evaluate(rule: SmartRule, library: Collection<Track>): List<Track> =
        library.asSequence()
            .filter { rule.matches(it) }
            .sortedWith(
                compareBy(
                    { it.effectiveAlbumArtist.sortNormalized() },
                    { it.effectiveAlbum.sortNormalized() },
                    { it.discNumber ?: 0 },
                    { it.trackNumber ?: 0 },
                    { it.title.sortNormalized() },
                ),
            )
            .toList()

    /**
     * Human-readable summary of a rule tree for list rows,
     * e.g. `(Genre is Rock OR Genre is Metal) AND Year > 1990`.
     */
    fun describe(rule: SmartRule): String = when (rule) {
        is SmartRule.Condition -> {
            val field = rule.field.name.lowercase().replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
            val op = when (rule.op) {
                com.tempobox.model.RuleOp.IS -> "is"
                com.tempobox.model.RuleOp.IS_NOT -> "is not"
                com.tempobox.model.RuleOp.CONTAINS -> "contains"
                com.tempobox.model.RuleOp.LESS_THAN -> "<"
                com.tempobox.model.RuleOp.GREATER_THAN -> ">"
            }
            "$field $op ${rule.value}"
        }
        is SmartRule.AllOf -> rule.rules.joinToString(" AND ") { wrap(it) }
        is SmartRule.AnyOf -> rule.rules.joinToString(" OR ") { wrap(it) }
    }

    private fun wrap(rule: SmartRule): String =
        if (rule is SmartRule.Condition) describe(rule) else "(${describe(rule)})"
}
