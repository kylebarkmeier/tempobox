package com.tempobox.model

/**
 * One group in the two-level editing view of a [SmartRule] tree: a run of
 * conditions combined with AND ([matchAll] = true) or OR.
 */
data class RuleGroup(
    val matchAll: Boolean,
    val conditions: List<SmartRule.Condition>,
)

/**
 * Converts between [SmartRule] trees and the two-level shape the auto-playlist
 * builder edits: groups of conditions, an AND/OR choice inside each group and
 * one between groups. That shape covers e.g. "(Genre is Rock OR Genre is
 * Metal) AND Year > 1990". [compose] builds the minimal tree for it.
 * [decompose] folds any stored tree back into it: every condition is kept,
 * but a branch nested deeper than two levels collapses to its top-level
 * operator, which can change what that branch matches. Builder-shaped trees
 * round-trip exactly, and nothing in the app stores deeper trees today; an
 * edit flow for arbitrary trees would need a lossless check first.
 */
object SmartRuleGroups {

    /** Minimal rule tree for [groups]; null when there is nothing to match. */
    fun compose(matchAllGroups: Boolean, groups: List<RuleGroup>): SmartRule? {
        val parts: List<SmartRule> = groups
            .filter { it.conditions.isNotEmpty() }
            .map { group ->
                group.conditions.singleOrNull()
                    ?: if (group.matchAll) {
                        SmartRule.AllOf(group.conditions)
                    } else {
                        SmartRule.AnyOf(group.conditions)
                    }
            }
        return when {
            parts.isEmpty() -> null
            parts.size == 1 -> parts.single()
            matchAllGroups -> SmartRule.AllOf(parts)
            else -> SmartRule.AnyOf(parts)
        }
    }

    /** Editing view of [rule]: match-all-groups flag plus the groups. */
    fun decompose(rule: SmartRule?): Pair<Boolean, List<RuleGroup>> = when (rule) {
        null -> true to emptyList()
        is SmartRule.Condition -> true to listOf(RuleGroup(matchAll = true, listOf(rule)))
        is SmartRule.AllOf -> true to toGroups(rule.rules, parentAll = true)
        is SmartRule.AnyOf -> false to toGroups(rule.rules, parentAll = false)
    }

    /**
     * A combinator of conditions only is a single group; one holding nested
     * combinators becomes one group per child, with top-level conditions
     * wrapped in single-condition groups so nothing is dropped.
     */
    private fun toGroups(rules: List<SmartRule>, parentAll: Boolean): List<RuleGroup> {
        if (rules.all { it is SmartRule.Condition }) {
            return listOf(RuleGroup(parentAll, rules.filterIsInstance<SmartRule.Condition>()))
                .filter { it.conditions.isNotEmpty() }
        }
        return rules.map { child ->
            when (child) {
                is SmartRule.Condition -> RuleGroup(matchAll = true, listOf(child))
                is SmartRule.AllOf -> RuleGroup(matchAll = true, leafConditions(child))
                is SmartRule.AnyOf -> RuleGroup(matchAll = false, leafConditions(child))
            }
        }.filter { it.conditions.isNotEmpty() }
    }

    /** Every leaf condition under [rule], in document order. */
    private fun leafConditions(rule: SmartRule): List<SmartRule.Condition> = when (rule) {
        is SmartRule.Condition -> listOf(rule)
        is SmartRule.AllOf -> rule.rules.flatMap(::leafConditions)
        is SmartRule.AnyOf -> rule.rules.flatMap(::leafConditions)
    }
}
