/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.tfv.deskflow.client.events

import org.tfv.deskflow.client.net.ConnectionFailure

sealed class ConnectionEvent : ClientEvent() {
    data object Connected : ConnectionEvent()
    data object Disconnected : ConnectionEvent()
    data object Rejected : ConnectionEvent()

    /** TLS handshake stopped because the server certificate is not pinned yet. */
    data class UntrustedServer(
        val address: String,
        val port: Int,
        val fingerprint: String,
    ) : ConnectionEvent()

    /**
     * A connection attempt failed before it was established. [consecutiveFailures] counts the attempts since the
     * last successful connection or the last change of the server.
     */
    data class ConnectFailed(
        val reason: ConnectionFailure,
        val consecutiveFailures: Int,
    ) : ConnectionEvent()

}