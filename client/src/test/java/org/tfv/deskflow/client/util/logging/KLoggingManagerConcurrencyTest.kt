// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.util.logging

import io.github.oshai.kotlinlogging.KLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** DSK-R13: loggers are created from several threads at once (socket thread, executors, main thread). */
class KLoggingManagerConcurrencyTest {

  @Test
  fun `concurrent lookups return one logger per name`() {
    val threads = 16
    val namesPerRound = 200
    val rounds = 20
    val pool = Executors.newFixedThreadPool(threads)
    try {
      repeat(rounds) { round ->
        val barrier = CyclicBarrier(threads)
        val seen = ConcurrentHashMap<String, MutableSet<KLogger>>()
        val futures = List(threads) {
          pool.submit {
            barrier.await()
            for (i in 0 until namesPerRound) {
              val name = "concurrency-$round-$i"
              val logger = KLoggingManager.logger(name)
              seen.computeIfAbsent(name) { ConcurrentHashMap.newKeySet() }.add(logger)
            }
          }
        }
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        val duplicates = seen.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "round $round: different logger instances for ${duplicates.size} names")
        // Every name is still there afterwards (a corrupted HashMap loses entries).
        for (i in 0 until namesPerRound) {
          val name = "concurrency-$round-$i"
          assertEquals(seen.getValue(name).single(), KLoggingManager.logger(name), "lost logger for $name")
        }
      }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `forwarding logger works on a plain JVM`() {
    // MessageHandler uses it; it threw an Error outside Android before, so the protocol could not run in tests.
    val logger = KLoggingManager.forwardingLogger("jvm-forwarding")
    assertEquals(logger, KLoggingManager.forwardingLogger("jvm-forwarding"))
  }
}
