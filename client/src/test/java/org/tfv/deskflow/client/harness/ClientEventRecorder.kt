// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.harness

import java.util.concurrent.CopyOnWriteArrayList
import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.MessagesEvent

/** Records what the client publishes on the [ClientEventBus] (raw [MessagesEvent]s are left out). */
class ClientEventRecorder : AutoCloseable {

  class Recorded(val event: ClientEvent, val atMs: Long)

  val events = CopyOnWriteArrayList<Recorded>()
  private val lock = Object()

  private val listener: (ClientEvent) -> Unit = { event ->
    if (event !is MessagesEvent) {
      events += Recorded(event, System.currentTimeMillis())
      synchronized(lock) { lock.notifyAll() }
    }
  }

  init {
    ClientEventBus.on(listener)
  }

  fun count(predicate: (ClientEvent) -> Boolean): Int = events.count { predicate(it.event) }

  /** Waits for the first recorded event (from index [from] on) matching [predicate]. */
  fun await(timeoutMs: Long = 5_000L, from: Int = 0, predicate: (ClientEvent) -> Boolean): Recorded {
    val deadline = System.currentTimeMillis() + timeoutMs
    synchronized(lock) {
      while (true) {
        events.drop(from).firstOrNull { predicate(it.event) }?.let { return it }
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) throw AssertionError("Event not seen within $timeoutMs ms; recorded: ${events.map { it.event }}")
        lock.wait(left)
      }
    }
  }

  /** True when no matching event (from index [from] on) arrives within [ms]. */
  fun noneWithin(ms: Long, from: Int = 0, predicate: (ClientEvent) -> Boolean): Boolean =
    try {
      await(ms, from, predicate)
      false
    } catch (_: AssertionError) {
      true
    }

  override fun close() {
    ClientEventBus.off(listener)
  }
}
