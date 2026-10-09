package com.example.locationtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MinuteRecordPolicyTest {

    private val interval = 60_000L
    private val grace = 15_000L

    private lateinit var policy: MinuteRecordPolicy

    @Before
    fun setUp() {

        policy =
            MinuteRecordPolicy(
                intervalMillis = interval,
                gracePeriodMillis = grace
            )
    }

    @Test
    fun beforeStart_neverRecordsStale() {

        assertFalse(policy.shouldRecordStale(Long.MAX_VALUE - 1))
    }

    @Test
    fun afterStart_waitsIntervalPlusGraceBeforeFirstStaleRecord() {

        policy.start(now = 0)

        assertFalse(policy.shouldRecordStale(interval + grace - 1))
        assertTrue(policy.shouldRecordStale(interval + grace))
    }

    @Test
    fun withoutSignal_recordsStaleEveryMinute() {

        policy.start(now = 0)

        var now = interval + grace
        val staleRecordTimes = mutableListOf<Long>()

        // Simula el temporizador: comprueba cada 5 s durante 5 minutos.
        while (now <= interval + grace + 5 * interval) {

            if (policy.shouldRecordStale(now)) {
                staleRecordTimes += now
                policy.onStaleRecorded(now)
            }

            now += 5_000
        }

        val gaps =
            staleRecordTimes.zipWithNext { a, b -> b - a }

        assertEquals(6, staleRecordTimes.size)
        assertTrue(gaps.all { it == interval })
    }

    @Test
    fun freshLocationEvery61s_neverCreatesStaleRecords() {

        policy.start(now = 0)

        var staleRecords = 0

        // 10 minutos: el GPS entrega cada 61 s y el temporizador
        // comprueba cada 5 s.
        for (now in 0L..600_000L step 1_000L) {

            if (now > 0 && now % 61_000L == 0L) {
                policy.onFreshLocation(fixTime = now, now = now)
            }

            if (now % 5_000L == 0L && policy.shouldRecordStale(now)) {
                staleRecords++
                policy.onStaleRecorded(now)
            }
        }

        assertEquals(0, staleRecords)
    }

    @Test
    fun freshLocation_postponesNextStaleRecord() {

        policy.start(now = 0)

        policy.onFreshLocation(fixTime = 1_000, now = 70_000)

        assertFalse(policy.shouldRecordStale(75_000))
        assertFalse(policy.shouldRecordStale(70_000 + interval + grace - 1))
        assertTrue(policy.shouldRecordStale(70_000 + interval + grace))
    }

    @Test
    fun repeatedFix_isNotNew() {

        policy.onFreshLocation(fixTime = 5_000, now = 0)

        assertFalse(policy.isNewFix(5_000))
        assertFalse(policy.isNewFix(4_999))
        assertTrue(policy.isNewFix(5_001))
    }

    @Test
    fun olderFix_doesNotMoveLastFixTimeBackwards() {

        policy.onFreshLocation(fixTime = 10_000, now = 0)
        policy.onFreshLocation(fixTime = 8_000, now = 1)

        assertEquals(10_000, policy.lastFixTime)
    }

    @Test
    fun stop_disablesStaleRecords() {

        policy.start(now = 0)
        policy.stop()

        assertFalse(policy.shouldRecordStale(10 * interval))
    }
}
