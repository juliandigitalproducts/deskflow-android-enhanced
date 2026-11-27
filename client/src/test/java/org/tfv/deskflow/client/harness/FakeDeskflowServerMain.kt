// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.harness

import kotlin.concurrent.thread

/**
 * Standalone fake server for the instrumented connect test (`ConnectFlowTest`): `./gradlew :client:runFakeServer`
 * (optional `-PharnessPort=24800`). Listens on all interfaces, so the emulator reaches it as 10.0.2.2. After each
 * handshake it enters the client screen, moves the mouse to (100, 200) and puts "deskflow-e2e" on the clipboard.
 */
fun main(args: Array<String>) {
  val port = args.firstOrNull()?.toIntOrNull() ?: 24800
  val server = FakeDeskflowServer(FakeDeskflowServer.Options(port = port, bindAddress = "0.0.0.0")).start()
  Runtime.getRuntime().addShutdownHook(Thread { server.close() })
  println("Fake Deskflow server on 0.0.0.0:${server.port} (CALV every 3 s) – Ctrl+C stops it")
  while (true) {
    val connection = server.pollConnection(1_000L) ?: continue
    println("Client #${connection.index} connected")
    thread(isDaemon = true) {
      try {
        connection.awaitHandshake(10_000L)
        println("Client #${connection.index}: handshake done, screen name \"${connection.screenName}\"")
        connection.send(FakeDeskflowServer.Proto.enter())
        connection.send(FakeDeskflowServer.Proto.mouseMove(100, 200))
        FakeDeskflowServer.Proto.clipboardText("deskflow-e2e").forEach(connection::send)
        connection.awaitClosedByClient(Long.MAX_VALUE)
        println("Client #${connection.index} disconnected")
      } catch (err: Throwable) {
        println("Client #${connection.index}: ${err.message}")
      }
    }
  }
}
