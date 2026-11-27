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

package org.tfv.deskflow.client.util.logging

import java.util.concurrent.ConcurrentHashMap
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.tfv.deskflow.client.util.isAndroidRuntime
import kotlin.reflect.KClass

object KLoggingManager {

    // Loggers are created from companion objects that initialise on different threads (socket thread, executors,
    // main thread); a plain HashMap is not safe for concurrent getOrPut.
    private val loggerMap = ConcurrentHashMap<String, KLogger>()
    private val forwardingLoggerMap = ConcurrentHashMap<String, KLogger>()

    init {
        try {
            System.setProperty("kotlin-logging-to-android-native", "true")
        } catch (err: Throwable) {
        }
    }

    inline fun <reified T> logger() = logger(T::class)
    fun logger(clazz: KClass<*>) = logger(clazz.java.simpleName)
    fun logger(name: String): KLogger = loggerMap.getOrPut(name) { KotlinLogging.logger(name) }

    private val androidLoggerClazz: Class<*>? = if (isAndroidRuntime()) {
        try {
            Class.forName("org.tfv.deskflow.logging.AndroidForwardingLogger")
        } catch (e: ClassNotFoundException) {
            null // Android logger not available
        }
    } else null

    fun forwardingLogger(name: String): KLogger = forwardingLoggerMap.getOrPut(name) {
        when (androidLoggerClazz) {
            null -> {
                // On a plain JVM (unit tests, the protocol harness) there is nothing to forward to: use a normal
                // logger. Throwing here made MessageHandler unusable outside Android (class init failed).
                if (isAndroidRuntime()) {
                    throw Error("Android logger not available, ensure the AndroidForwardingLogger class is in your classpath.")
                }
                KotlinLogging.logger(name)
            }

            else -> androidLoggerClazz.getDeclaredConstructor(String::class.java)
                .newInstance(name) as KLogger
        }
    }

    fun forwardingLogger(clazz: KClass<*>) = forwardingLogger(clazz.java.simpleName)

    inline fun <reified T> forwardingLogger(): KLogger {
        return forwardingLogger(T::class)
    }
}

