package com.livevip.app.streaming

import kotlin.random.Random

/**
 * Pure playlist sequencing logic — no Android dependencies, fully unit tested.
 *
 * The engine decides WHICH item plays next; HOW the switch happens without
 * touching the encoder/RTMP session is the job of [com.livevip.app.streaming.LiveStreamingManager].
 *
 * Modes:
 *  - [LoopMode.LOOP_ONE]  : the current item repeats internally (decoder loop),
 *                           manual skip still moves through the list.
 *  - [LoopMode.LOOP_ALL]  : in order, wraps around, forever.
 *  - [LoopMode.SHUFFLE]   : random order forever, never repeating an item back-to-back.
 *  - [LoopMode.PLAY_ONCE] : in order, one pass, then the broadcast ends (explicit user choice).
 */
class PlaylistEngine<T : Any>(
    items: List<T>,
    private var mode: LoopMode = LoopMode.LOOP_ALL,
    private val random: Random = Random.Default
) {

    private val items = items.toList()
    private var shuffledQueue: MutableList<Int> = mutableListOf()
    private var shuffleCursor = 0

    /** Index of the currently playing item. */
    var currentIndex: Int = 0
        private set

    /**
     * Number of completed item boundaries (video → video transitions, including
     * internal decoder loops). The live stream timer is NEVER reset by these.
     */
    var boundaryCount: Int = 0
        private set

    /** Completed full passes of the playlist. */
    var passesCompleted: Int = 0
        private set

    /** Consecutive load failures — used to stop pathological retry loops. */
    var consecutiveFailures: Int = 0
        private set

    init {
        require(items.isNotEmpty()) { "Playlist cannot be empty" }
    }

    val size: Int get() = items.size
    val current: T get() = items[currentIndex]
    val currentMode: LoopMode get() = mode
    val hasMultipleItems: Boolean get() = items.size > 1

    /**
     * True when the current item should loop INSIDE the decoder (no source
     * switch needed at all — the cheapest, most seamless boundary).
     */
    val usesInternalLoop: Boolean
        get() = mode == LoopMode.LOOP_ONE || (items.size == 1 && mode != LoopMode.PLAY_ONCE)

    /** Playlist is allowed to end by itself only in PLAY_ONCE mode. */
    val canEndNaturally: Boolean get() = mode == LoopMode.PLAY_ONCE && items.size >= 1

    fun setMode(newMode: LoopMode) {
        if (newMode == mode) return
        mode = newMode
        if (newMode == LoopMode.SHUFFLE) reshuffle(avoidImmediateRepeat = false)
    }

    fun itemAt(index: Int): T = items[index]

    /** Register an internal decoder loop of the current item (same file restarted). */
    fun onInternalLoop() {
        boundaryCount++
    }

    /**
     * The current item reached end-of-file. Returns the next item to play, or
     * null when the playlist is finished (possible only in PLAY_ONCE).
     *
     * For LOOP_ONE this returns the current item unchanged (defensive — the
     * decoder should loop internally instead of reporting EOF).
     */
    fun onItemFinished(): T? {
        boundaryCount++
        consecutiveFailures = 0
        if (mode == LoopMode.LOOP_ONE) return current
        return when (mode) {
            LoopMode.LOOP_ALL, LoopMode.PLAY_ONCE -> linearNext()
            LoopMode.SHUFFLE -> shuffleNext()
            LoopMode.LOOP_ONE -> current
        }
    }

    /**
     * The current item failed to load. Advances like a normal boundary; returns
     * the next candidate or null when nothing remains / failure budget exhausted.
     */
    fun onItemError(maxConsecutiveFailures: Int = 3): T? {
        consecutiveFailures++
        if (consecutiveFailures >= maxConsecutiveFailures) return null
        return when (mode) {
            LoopMode.LOOP_ONE -> items[(currentIndex + 1) % items.size].also { currentIndex = (currentIndex + 1) % items.size }
            LoopMode.LOOP_ALL, LoopMode.PLAY_ONCE -> linearNext()
            LoopMode.SHUFFLE -> shuffleNext()
        }
    }

    /**
     * Manual skip forward (user action during live). Never ends the broadcast —
     * LOOP_ONE keeps looping the new item; PLAY_ONCE wraps like LOOP_ALL on
     * manual skip because the user explicitly asked to keep going.
     */
    fun skipToNext(): T {
        boundaryCount++
        return when (mode) {
            LoopMode.LOOP_ONE -> {
                currentIndex = (currentIndex + 1) % items.size
                current
            }
            LoopMode.LOOP_ALL, LoopMode.PLAY_ONCE -> linearNext() ?: items[0].also { currentIndex = 0 }
            LoopMode.SHUFFLE -> shuffleNext() ?: items[0].also { currentIndex = 0 }
        }
    }

    /** Manual skip backward (user action during live). */
    fun skipToPrevious(): T {
        boundaryCount++
        currentIndex = (currentIndex - 1 + items.size) % items.size
        return current
    }

    /** Jump to a specific index (user tapped a playlist row). */
    fun jumpTo(index: Int): T {
        if (index !in items.indices) return current
        boundaryCount++
        currentIndex = index
        if (mode == LoopMode.SHUFFLE) reshuffle(avoidImmediateRepeat = true)
        return current
    }

    fun indexOf(item: T): Int = items.indexOf(item)

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun linearNext(): T? {
        val next = currentIndex + 1
        return if (next < items.size) {
            currentIndex = next
            items[next]
        } else {
            if (mode == LoopMode.PLAY_ONCE) {
                passesCompleted++
                null // playlist finished — broadcast may end (user chose this)
            } else {
                passesCompleted++
                currentIndex = 0
                items[0] // LOOP_ALL wraps around
            }
        }
    }

    private fun shuffleNext(): T? {
        if (shuffledQueue.isEmpty()) reshuffle(avoidImmediateRepeat = true)
        if (shuffleCursor >= shuffledQueue.size) {
            reshuffle(avoidImmediateRepeat = true)
        }
        if (shuffledQueue.isEmpty()) return null
        val nextIndex = shuffledQueue[shuffleCursor]
        shuffleCursor++
        currentIndex = nextIndex
        return items[nextIndex]
    }

    private fun reshuffle(avoidImmediateRepeat: Boolean) {
        val order = items.indices.toMutableList()
        do {
            order.shuffle(random)
        } while (avoidImmediateRepeat &&
            order.isNotEmpty() && items.size > 1 &&
            order.first() == currentIndex
        )
        shuffledQueue = order
        shuffleCursor = 0
    }
}
