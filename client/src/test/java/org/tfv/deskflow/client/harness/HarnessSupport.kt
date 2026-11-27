// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.harness

import org.tfv.deskflow.client.Client
import org.tfv.deskflow.client.manager.ClipboardSendManager
import org.tfv.deskflow.client.models.ServerTarget

const val HARNESS_SCREEN_NAME = "Harness-Tablet"
const val HARNESS_WIDTH = 1080
const val HARNESS_HEIGHT = 2400

fun harnessTarget(port: Int, address: String = "127.0.0.1") =
  ServerTarget(
    screenName = HARNESS_SCREEN_NAME,
    address = address,
    port = port,
    useTls = false,
    width = HARNESS_WIDTH,
    height = HARNESS_HEIGHT,
  )

/** A fake server, a client pointed at it and an event recorder, all closed together. */
class HarnessSession(
  serverOptions: FakeDeskflowServer.Options = FakeDeskflowServer.Options(),
  clipboardReceiveEnabled: () -> Boolean = { true },
) : AutoCloseable {
  val server = FakeDeskflowServer(serverOptions).start()
  val events = ClientEventRecorder()
  val client = Client(clipboardReceiveEnabledProvider = clipboardReceiveEnabled)

  init {
    ClipboardSendManager.clearPendingClipboardDataMessages()
    client.setTarget(harnessTarget(server.port))
  }

  /** Waits for the next connection and its finished handshake. */
  fun connected(timeoutMs: Long = 5_000L): FakeDeskflowServer.Connection =
    server.awaitConnection(timeoutMs).awaitHandshake(timeoutMs)

  override fun close() {
    client.dispose()
    events.close()
    server.close()
  }
}
