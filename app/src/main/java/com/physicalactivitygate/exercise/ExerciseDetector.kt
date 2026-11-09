package com.physicalactivitygate.exercise

import kotlin.math.acos
import kotlin.math.sqrt

data class Point(val x: Float, val y: Float)

enum class SquatState { STANDING, DESCENDING, BOTTOM, ASCENDING }

class SquatDetector(
    private val standingAngle: Float = 155f,
    private val bottomAngle: Float = 105f,
    private val cooldownMs: Long = 500L,
    private val minRepMs: Long = 450L,
    private val maxRepMs: Long = 6_000L
) {
    private var state = SquatState.STANDING
    private var lastCountAt = -cooldownMs
    private var repStartedAt = 0L
    var count = 0
        private set

    fun update(kneeAngle: Float, now: Long = System.currentTimeMillis()): Int {
        if (now - lastCountAt < cooldownMs && state == SquatState.STANDING) return count
        when (state) {
            SquatState.STANDING -> if (kneeAngle < standingAngle) { state = SquatState.DESCENDING; repStartedAt = now }
            SquatState.DESCENDING -> when {
                now - repStartedAt > maxRepMs -> { state = SquatState.STANDING; repStartedAt = 0L }
                kneeAngle <= bottomAngle -> state = SquatState.BOTTOM
                kneeAngle >= standingAngle -> { state = SquatState.STANDING; repStartedAt = 0L }
            }
            SquatState.BOTTOM -> if (now - repStartedAt > maxRepMs) { state = SquatState.STANDING; repStartedAt = 0L } else if (kneeAngle > bottomAngle + 12f) state = SquatState.ASCENDING
            SquatState.ASCENDING -> when {
                now - repStartedAt > maxRepMs -> { state = SquatState.STANDING; repStartedAt = 0L }
                kneeAngle >= standingAngle -> {
                    if (now - repStartedAt >= minRepMs) count++
                    lastCountAt = now
                    state = SquatState.STANDING
                    repStartedAt = 0L
                }
            }
        }
        return count
    }

    fun reset() { state = SquatState.STANDING; count = 0; lastCountAt = -cooldownMs; repStartedAt = 0L }
}

fun angleDegrees(a: Point, b: Point, c: Point): Float {
    val abx = a.x - b.x; val aby = a.y - b.y
    val cbx = c.x - b.x; val cby = c.y - b.y
    val dot = abx * cbx + aby * cby
    val ab = sqrt(abx * abx + aby * aby)
    val cb = sqrt(cbx * cbx + cby * cby)
    if (ab == 0f || cb == 0f) return 180f
    val cosine = (dot / (ab * cb)).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cosine.toDouble())).toFloat()
}

class PushUpDetector(private val cooldownMs: Long = 500L, private val minRepMs: Long = 400L, private val maxRepMs: Long = 5_000L) {
    private enum class State { TOP, DESCENDING, BOTTOM, ASCENDING }
    private var state = State.TOP
    private var last = 0L
    private var repStartedAt = 0L
    var count = 0; private set
    fun update(elbowAngle: Float, now: Long = System.currentTimeMillis()): Int {
        when (state) {
            State.TOP -> if (elbowAngle < 120f) { state = State.DESCENDING; repStartedAt = now }
            State.DESCENDING -> when {
                now - repStartedAt > maxRepMs -> { state = State.TOP; repStartedAt = 0L }
                elbowAngle < 85f -> state = State.BOTTOM
                elbowAngle > 155f -> { state = State.TOP; repStartedAt = 0L }
            }
            State.BOTTOM -> if (now - repStartedAt > maxRepMs) { state = State.TOP; repStartedAt = 0L } else if (elbowAngle > 105f) state = State.ASCENDING
            State.ASCENDING -> if (elbowAngle > 155f) {
                if (now - repStartedAt in minRepMs..maxRepMs && now - last >= cooldownMs) count++
                last = now; state = State.TOP; repStartedAt = 0L
            }
        }
        return count
    }
}

class JumpingJackDetector(private val maxOpenMs: Long = 4_000L, private val minOpenMs: Long = 250L, private val cooldownMs: Long = 450L) {
    private enum class State { CLOSED, OPEN }
    private var state = State.CLOSED
    private var openedAt = 0L
    private var lastCountAt = -cooldownMs
    var count = 0; private set
    fun update(wristsUp: Boolean, feetApart: Boolean, now: Long = System.currentTimeMillis()): Int {
        val open = wristsUp && feetApart
        when (state) {
            State.CLOSED -> if (open) { state = State.OPEN; openedAt = now }
            State.OPEN -> when {
                !open && !wristsUp && !feetApart && now - openedAt in minOpenMs..maxOpenMs && now - lastCountAt >= cooldownMs -> {
                    count++; lastCountAt = now; state = State.CLOSED
                }
                now - openedAt > maxOpenMs -> state = State.CLOSED
            }
        }
        return count
    }
    fun reset() { state = State.CLOSED; openedAt = 0L; lastCountAt = -cooldownMs; count = 0 }
}

class PlankDetector {
    private var startedAt: Long? = null
    var seconds = 0; private set
    fun update(validAlignment: Boolean, now: Long = System.currentTimeMillis()): Int {
        if (validAlignment) {
            if (startedAt == null) startedAt = now
            seconds = ((now - (startedAt ?: now)) / 1000L).toInt().coerceAtLeast(0)
        } else {
            startedAt = null
            seconds = 0
        }
        return seconds
    }
    fun reset() { startedAt = null; seconds = 0 }
}

class SitUpDetector(private val cooldownMs: Long = 650L, private val minRepMs: Long = 500L, private val maxRepMs: Long = 6_000L) {
    private enum class State { OPEN, CLOSED }
    private var state = State.OPEN
    private var last = 0L
    private var repStartedAt = 0L
    var count = 0; private set
    fun update(torsoAngle: Float, now: Long = System.currentTimeMillis()): Int {
        when (state) {
            State.OPEN -> if (torsoAngle < 70f) { state = State.CLOSED; repStartedAt = now }
            State.CLOSED -> if (torsoAngle > 125f) {
                if (now - repStartedAt in minRepMs..maxRepMs && now - last >= cooldownMs) count++
                last = now; state = State.OPEN; repStartedAt = 0L
            } else if (now - repStartedAt > maxRepMs) { state = State.OPEN; repStartedAt = 0L }
        }
        return count
    }
}
