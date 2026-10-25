package com.tempobox.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The builder's two-level view of rule trees: compose/decompose round trips. */
class SmartRuleGroupsTest {

    private val rock = SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock")
    private val metal = SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Metal")
    private val nineties = SmartRule.Condition(RuleField.YEAR, RuleOp.GREATER_THAN, "1990")
    private val good = SmartRule.Condition(RuleField.RATING, RuleOp.GREATER_THAN, "3")

    // ------------------------------------------------------------------ compose

    @Test
    fun `one single-condition group composes to the bare condition`() {
        val rule = SmartRuleGroups.compose(true, listOf(RuleGroup(true, listOf(rock))))
        assertThat(rule).isEqualTo(rock)
    }

    @Test
    fun `one multi-condition group composes to its combinator alone`() {
        assertThat(SmartRuleGroups.compose(true, listOf(RuleGroup(true, listOf(rock, nineties)))))
            .isEqualTo(SmartRule.AllOf(listOf(rock, nineties)))
        assertThat(SmartRuleGroups.compose(true, listOf(RuleGroup(false, listOf(rock, metal)))))
            .isEqualTo(SmartRule.AnyOf(listOf(rock, metal)))
    }

    @Test
    fun `multiple groups nest under the top-level combinator`() {
        val rule = SmartRuleGroups.compose(
            true,
            listOf(
                RuleGroup(matchAll = false, listOf(rock, metal)),
                RuleGroup(matchAll = true, listOf(nineties)),
            ),
        )
        assertThat(rule).isEqualTo(
            SmartRule.AllOf(listOf(SmartRule.AnyOf(listOf(rock, metal)), nineties)),
        )
    }

    @Test
    fun `empty groups are dropped and an all-empty list composes to null`() {
        assertThat(SmartRuleGroups.compose(true, emptyList())).isNull()
        assertThat(SmartRuleGroups.compose(true, listOf(RuleGroup(true, emptyList())))).isNull()
        assertThat(
            SmartRuleGroups.compose(
                false,
                listOf(RuleGroup(true, emptyList()), RuleGroup(true, listOf(rock))),
            ),
        ).isEqualTo(rock)
    }

    // ---------------------------------------------------------------- decompose

    @Test
    fun `null decomposes to no groups`() {
        assertThat(SmartRuleGroups.decompose(null)).isEqualTo(true to emptyList<RuleGroup>())
    }

    @Test
    fun `a bare condition becomes one single-condition group`() {
        val (matchAll, groups) = SmartRuleGroups.decompose(rock)
        assertThat(matchAll).isTrue()
        assertThat(groups).containsExactly(RuleGroup(matchAll = true, listOf(rock)))
    }

    @Test
    fun `a flat combinator becomes one group carrying its operator`() {
        val (allTop, allGroups) = SmartRuleGroups.decompose(SmartRule.AllOf(listOf(rock, nineties)))
        assertThat(allTop).isTrue()
        assertThat(allGroups).containsExactly(RuleGroup(matchAll = true, listOf(rock, nineties)))

        val (anyTop, anyGroups) = SmartRuleGroups.decompose(SmartRule.AnyOf(listOf(rock, metal)))
        assertThat(anyTop).isFalse()
        assertThat(anyGroups).containsExactly(RuleGroup(matchAll = false, listOf(rock, metal)))
    }

    @Test
    fun `nested combinators become groups and loose conditions get their own`() {
        val (matchAll, groups) = SmartRuleGroups.decompose(
            SmartRule.AllOf(
                listOf(SmartRule.AnyOf(listOf(rock, metal)), nineties, good),
            ),
        )
        assertThat(matchAll).isTrue()
        assertThat(groups).containsExactly(
            RuleGroup(matchAll = false, listOf(rock, metal)),
            RuleGroup(matchAll = true, listOf(nineties)),
            RuleGroup(matchAll = true, listOf(good)),
        ).inOrder()
    }

    @Test
    fun `nesting deeper than two levels keeps every condition`() {
        val deep = SmartRule.AllOf(
            listOf(
                SmartRule.AnyOf(listOf(rock, SmartRule.AllOf(listOf(metal, good)))),
                nineties,
            ),
        )
        val (_, groups) = SmartRuleGroups.decompose(deep)
        assertThat(groups.flatMap { it.conditions })
            .containsExactly(rock, metal, good, nineties)
    }

    // ---------------------------------------------------------------- round trip

    @Test
    fun `builder-shaped trees survive a decompose-compose round trip`() {
        val shapes = listOf<SmartRule>(
            rock,
            SmartRule.AllOf(listOf(rock, nineties)),
            SmartRule.AnyOf(listOf(rock, metal)),
            SmartRule.AllOf(listOf(SmartRule.AnyOf(listOf(rock, metal)), nineties)),
            SmartRule.AnyOf(
                listOf(SmartRule.AllOf(listOf(rock, nineties)), SmartRule.AllOf(listOf(metal, good))),
            ),
        )
        shapes.forEach { rule ->
            val (matchAll, groups) = SmartRuleGroups.decompose(rule)
            assertThat(SmartRuleGroups.compose(matchAll, groups)).isEqualTo(rule)
        }
    }
}
