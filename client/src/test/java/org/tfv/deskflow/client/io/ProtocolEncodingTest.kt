// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.tfv.deskflow.client.io.msgs.HelloBackMessage

/** UTF-8 strings, Synergy-style hello and buffer handling (fixes from the 10 Nov 2026 review). */
class ProtocolEncodingTest {

  @Test
  fun `string length field counts UTF-8 bytes`() {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).writeString("Jürgens-Tablet")
    val bytes = out.toByteArray()
    val din = DataInputStream(ByteArrayInputStream(bytes))
    val len = din.readInt()
    assertEquals("Jürgens-Tablet".toByteArray(Charsets.UTF_8).size, len)
    assertEquals(bytes.size - 4, len)
  }

  @Test
  fun `hello back keeps a non-ASCII screen name intact`() {
    val out = ByteArrayOutputStream()
    HelloBackMessage(1, 6, "Café-Tablet").writeData(DataOutputStream(out))
    val data = out.toByteArray()
    val parsed = HelloBackMessage()
    parsed.readData(DataInputStream(ByteArrayInputStream(data)), data.size)
    assertEquals("Café-Tablet", parsed.name)
  }

  @Test
  fun `a Synergy-style server hello without name is read as such`() {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).apply { writeShort(1); writeShort(8) }
    val data = out.toByteArray()
    val parsed = HelloBackMessage(name = "x")
    parsed.readData(DataInputStream(ByteArrayInputStream(data)), data.size)
    assertEquals(1, parsed.majorVersion)
    assertEquals(8, parsed.minorVersion)
    assertEquals("", parsed.name)
  }

  @Test
  fun `pop only keeps the unread bytes and the buffer shrinks again`() {
    val buf = DynamicByteBuffer(16)
    val big = ByteArray(1024 * 1024) { (it % 251).toByte() }
    buf.append(big)
    assertEquals(big.size, buf.size())
    val first = buf.pop(10)
    assertArrayEquals(big.copyOfRange(0, 10), first)
    val rest = buf.pop(big.size - 10)
    assertArrayEquals(big.copyOfRange(10, big.size), rest)
    assertEquals(0, buf.size())
    // The 1 MiB backing array was given back.
    assertEquals(16, buf.capacity)
  }
}
