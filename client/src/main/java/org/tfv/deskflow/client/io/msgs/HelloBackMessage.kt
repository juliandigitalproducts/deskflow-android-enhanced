/*
 * deskflow -- mouse and keyboard sharing utility
 * Copyright (C) 2010 Shaun Patterson
 * Copyright (C) 2010 The Deskflow Project
 * Copyright (C) 2009 The Deskflow+ Project
 * Copyright (C) 2002 Chris Schoeneman
 * Copyright (C) 2025 Jonathan Glanz
 * Copyright (C) 2026 juliandigitalproducts
 * 
 * This package is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2, found in the file COPYING that should have accompanied this file.
 * 
 * This package is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tfv.deskflow.client.io.msgs

import org.tfv.deskflow.client.io.writeString
import java.io.DataInputStream
import java.io.DataOutputStream

class HelloBackMessage(// Protocol version and screen name
    var majorVersion: Int = 0,
    var minorVersion: Int = 0,
    // No host-name lookup as default: messages are created on the socket thread while parsing (M4).
    var name: String = ""
) : Message(MessageType.HELLOBACK) {

    override fun readData(inStream: DataInputStream, dataSize: Int) {
        majorVersion = inStream.readShort().toInt()
        minorVersion = inStream.readShort().toInt()
        // A server speaking the "Synergy" protocol name sends its hello in this shape, without a name.
        // The name is length-prefixed UTF-8 (as written by writeString), not a fixed 4-byte code.
        name = if (dataSize > 4 && inStream.available() >= 4) {
            val len = inStream.readInt()
            require(len in 0..MAX_NAME_BYTES && len <= inStream.available()) { "invalid screen name length $len" }
            ByteArray(len).also { inStream.readFully(it) }.toString(Charsets.UTF_8)
        } else ""
    }

    override fun writeData(outStream: DataOutputStream) {
        outStream.writeShort(majorVersion)
        outStream.writeShort(minorVersion)
        outStream.writeString(name)
    }


    private companion object {
        const val MAX_NAME_BYTES = 1024
    }
}
