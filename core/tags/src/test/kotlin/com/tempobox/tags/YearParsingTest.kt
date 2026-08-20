package com.tempobox.tags

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Year parsing covers the mess found in real tags: TYER ("1994"),
 * TDRC ("1994-06-21"), and free-form DATE fields.
 */
class YearParsingTest {

    @Test
    fun `plain four-digit years parse`() {
        assertThat(JAudioTaggerIO.parseYear("1994")).isEqualTo(1994)
        assertThat(JAudioTaggerIO.parseYear("2024")).isEqualTo(2024)
    }

    @Test
    fun `ISO dates parse to their year`() {
        assertThat(JAudioTaggerIO.parseYear("1994-06-21")).isEqualTo(1994)
        assertThat(JAudioTaggerIO.parseYear("2011-01")).isEqualTo(2011)
    }

    @Test
    fun `day-first dates find the four-digit run`() {
        assertThat(JAudioTaggerIO.parseYear("21/06/1994")).isEqualTo(1994)
    }

    @Test
    fun `garbage and blanks return null`() {
        assertThat(JAudioTaggerIO.parseYear("")).isNull()
        assertThat(JAudioTaggerIO.parseYear("unknown")).isNull()
        assertThat(JAudioTaggerIO.parseYear("12")).isNull()
    }

    @Test
    fun `implausible years are rejected`() {
        assertThat(JAudioTaggerIO.parseYear("0000")).isNull()
        assertThat(JAudioTaggerIO.parseYear("9999")).isNull()
    }
}
