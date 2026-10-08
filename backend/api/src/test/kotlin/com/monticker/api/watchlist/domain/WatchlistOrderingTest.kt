package com.monticker.api.watchlist.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WatchlistOrderingTest {

    private val ids = listOf(10L, 20L, 30L, 40L)

    @Test
    fun `moves an item up and down`() {
        assertThat(WatchlistOrdering.move(ids, 30L, 1)).containsExactly(10L, 30L, 20L, 40L)
        assertThat(WatchlistOrdering.move(ids, 20L, 2)).containsExactly(10L, 30L, 20L, 40L)
        assertThat(WatchlistOrdering.move(ids, 40L, 0)).containsExactly(40L, 10L, 20L, 30L)
    }

    @Test
    fun `moving to its own place keeps the order`() {
        assertThat(WatchlistOrdering.move(ids, 20L, 1)).isEqualTo(ids)
    }

    @Test
    fun `index past the end appends to the end`() {
        assertThat(WatchlistOrdering.move(ids, 10L, 99)).containsExactly(20L, 30L, 40L, 10L)
    }

    @Test
    fun `negative index and unknown item are rejected`() {
        assertThatThrownBy { WatchlistOrdering.move(ids, 10L, -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WatchlistOrdering.move(ids, 99L, 0) }.isInstanceOf(NoSuchElementException::class.java)
    }
}
