package com.tempobox.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwatchNameTest {

    @Test
    fun `every preset swatch has a distinct name`() {
        val names = SWATCHES.map { it.second }
        assertThat(names).containsNoDuplicates()
        names.forEach { assertThat(it).isNotEmpty() }
    }

    @Test
    fun `preset colors resolve to their names`() {
        assertThat(swatchName(0xFF2E6C2F)).isEqualTo("Green")
        assertThat(swatchName(0xFF9C4146)).isEqualTo("Maroon")
        assertThat(swatchName(0xFF6750A4)).isEqualTo("Purple")
    }

    @Test
    fun `non-preset colors fall back to a hex code`() {
        assertThat(swatchName(0xFF123456)).isEqualTo("#123456")
        assertThat(swatchName(0xFFABCDEF)).isEqualTo("#ABCDEF")
    }
}
