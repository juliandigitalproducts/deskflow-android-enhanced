// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.io

import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MessageParserLimitsTest {

  private fun bufferWith(vararg ints: Int): DynamicByteBuffer {
    val bb = ByteBuffer.allocate(ints.size * 4)
    ints.forEach { bb.putInt(it) }
    bb.flip()
    return DynamicByteBuffer().apply { append(bb, bb.remaining()) }
  }

  @Test
  fun `huge length from server is rejected instead of allocating`() {
    assertThrows(InvalidMessageSizeException::class.java) {
      MessageParser().parseBuffer(bufferWith(Int.MAX_VALUE))
    }
  }

  @Test
  fun `negative length is rejected`() {
    assertThrows(InvalidMessageSizeException::class.java) {
      MessageParser().parseBuffer(bufferWith(-5))
    }
  }

  @Test
  fun `length bytes above 0x7F are read unsigned`() {
    // 200 = 0x000000C8: the last byte has the high bit set.
    assertEquals(0, MessageParser().parseBuffer(bufferWith(200)).size)
  }

  @Test
  fun `incomplete message waits for more data`() {
    assertEquals(0, MessageParser().parseBuffer(bufferWith(100, 0)).size)
  }
}
