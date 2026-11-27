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

package org.tfv.deskflow.services

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import arrow.core.raise.catch
import io.github.oshai.kotlinlogging.Level
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.tfv.deskflow.BuildConfig
import org.tfv.deskflow.client.Client
import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.net.CertificateFingerprint
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.net.ConnectionFailure
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.ext.toDebugString
import org.tfv.deskflow.client.manager.ClipboardSendManager
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.models.SERVER_DEFAULT_ADDRESS
import org.tfv.deskflow.components.AutoConnectSelector
import org.tfv.deskflow.components.ClientCertificate
import org.tfv.deskflow.components.ServerDiscovery
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.data.aidl.ConnectionState
import org.tfv.deskflow.data.aidl.IConnectionService
import org.tfv.deskflow.data.aidl.IConnectionServiceCallback
import org.tfv.deskflow.data.aidl.Result
import org.tfv.deskflow.data.aidl.ScreenState
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.data.models.AppPrefs
import org.tfv.deskflow.data.models.AppPrefsKt.ScreenConfigKt.serverConfig
import org.tfv.deskflow.data.models.AppPrefsKt.loggingConfig
import org.tfv.deskflow.data.models.AppPrefsKt.screenConfig
import org.tfv.deskflow.data.models.copy
import org.tfv.deskflow.ext.copy
import org.tfv.deskflow.ext.toServerTarget
import org.tfv.deskflow.logging.AndroidForwardingLogger
import org.tfv.deskflow.logging.LogRecordEvent

class ConnectionService : Service() {
  companion object {
    private val log = KLoggingManager.forwardingLogger<ConnectionService>()

    private const val AUTO_DISCOVERY_INITIAL_DELAY_MS = 3_000L
    private const val AUTO_DISCOVERY_RECONNECT_GRACE_MS = 15_000L
    private const val AUTO_DISCOVERY_TICK_MS = 5_000L
    private const val AUTO_DISCOVERY_MIN_BACKOFF_MS = 5_000L
    private const val AUTO_DISCOVERY_MAX_BACKOFF_MS = 5 * 60_000L
  }

  private var connectionStateUpdateJob: Job? = null

  private var autoDiscoveryJob: Job? = null

  private val serverDiscovery by lazy { ServerDiscovery(this) }

  private val serviceScope =
    CoroutineScope(Dispatchers.Default + SupervisorJob())

  private val broadcastLock = Any()
  private var broadcastThread: Thread? = null
  private val broadcastThreadFactory = ThreadFactory { r ->
    synchronized(broadcastLock) {
      if (broadcastThread == null)
        broadcastThread = Thread(r, "BroadcastEventLoopThread")

      return@ThreadFactory broadcastThread
    }
  }
  private val broadcastExecutor =
    Executors.newSingleThreadExecutor(broadcastThreadFactory)

  /** Confirmed server certificate fingerprints, mirrored from AppPrefs. */
  @Volatile private var trustedFingerprints: Set<String> = emptySet()

  /** Clipboard sharing switch, mirrored from AppPrefs; while off, clipboard data is not even collected (DSK-R3). */
  @Volatile private var clipboardSyncEnabled = false

  private val client =
    Client(
      keyManagersProvider = { ClientCertificate.keyManagers() },
      trustedFingerprintsProvider = { trustedFingerprints },
      clipboardReceiveEnabledProvider = { clipboardSyncEnabled },
    )

  private val clientEventListener: (ClientEvent) -> Unit = { onClientEvent(it) }

  /** The connection state model */
  private lateinit var connectionStateModel: ConnectionStateModel

  private val connectionCallbacks =
    RemoteCallbackList<IConnectionServiceCallback>()

  private val binder =
    object : IConnectionService.Stub() {

      override fun setClipboardData(bundle: Bundle): Result {
        return catch({
          val clipboardData = bundle.getSerializable("clipboardData",ClipboardData::class.java)
          require(clipboardData != null) {
            "clipboardData was not valid in clipboard data bundle"
          }
          client.sendClipboard(clipboardData)
          return Result().apply {
            ok = true
            message = ""
          }
        }) { err: Throwable ->
          log.error(err) { "Failed to set clipboard data" }
          return Result().apply {
            ok = false
            message = err.message
          }
        }

      }

      override fun setEnabled(enabled: Boolean): Result {
        connectionStateModel.updateState {
          it.copy(isEnabled = enabled, failureKind = ConnectionFailure.NONE_CODE, failedAttempts = 0)
        }

        return Result().apply {
          ok = true
          message = ""
        }
      }

      override fun setCaptureKeyMode(enabled: Boolean): Result {
        connectionStateModel.updateState {
          it.copy(isCaptureKeyModeEnabled = enabled)
        }
        return Result().apply {
          ok = true
          message = ""
        }
      }

      override fun setLogForwardingLevel(levelName: String?): Result {
        val result =
          Result().apply {
            ok = false
            message = ""
          }
        if (levelName != null) {
          catch({
            val level = Level.valueOf(levelName)
            serviceScope.launch {
              appPrefsStore.updateData { appPrefs ->
                appPrefs.copy {
                  loggingConfig {
                    forwardingLevel = level.name
                    forwardingEnabled = true
                  }
                }
              }
            }
            AndroidForwardingLogger.forwardingLevel = level
            result.ok = true
          }) { err: Throwable ->
            log.error(err) { "Invalid log level: $levelName" }
            result.message = err.message
          }
        } else {
          result.message = "levelName is null"
        }

        return result
      }

      override fun registerCallback(callback: IConnectionServiceCallback?) {
        if (callback != null) connectionCallbacks.register(callback)
      }

      override fun unregisterCallback(callback: IConnectionServiceCallback?) {
        if (callback != null) connectionCallbacks.unregister(callback)
      }

      override fun updateScreenState(screenState: ScreenState?): Result {
        val res = Result()
        if (screenState == null)
          return res.apply {
            ok = false
            message = "screenState is null"
          }
        // Binder calls from the UI run on its main thread in this process: write
        // asynchronously instead of blocking it with runBlocking (code review D29).
        serviceScope.launch {
          appPrefsStore.updateData { appPrefs ->
            return@updateData appPrefs
              .toBuilder()
              .apply {
                screen = screenConfig {
                  name = screenState.name
                  server = serverConfig {
                    address = screenState.server.address
                    port = screenState.server.port
                    useTls = screenState.server.useTls
                    autoDiscoverDisabled = !screenState.server.autoDiscover
                  }
                }
                setupCompleted = true
              }
              .build()
          }
        }

        return res.apply { ok = true }
      }

      override fun getState(): ConnectionState {
        // StateFlow already holds the value; stateIn() here started a new
        // never-ending collector on every call.
        return connectionStateModel.state.value
      }

      override fun trustServer(fingerprint: String?): Result {
        val normalized = fingerprint?.let { CertificateFingerprint.normalize(it) }
        val pending = connectionStateModel.state.value.pendingFingerprint
        if (normalized.isNullOrBlank() || normalized != pending) {
          return Result().apply {
            ok = false
            message = "Fingerprint does not match the waiting server"
          }
        }
        trustedFingerprints = trustedFingerprints + normalized
        serviceScope.launch {
          appPrefsStore.updateData { appPrefs ->
            if (normalized in appPrefs.trustedServerFingerprintsList) appPrefs
            else appPrefs.toBuilder().addTrustedServerFingerprints(normalized).build()
          }
        }
        log.info { "Server certificate trusted: $normalized" }
        connectionStateModel.updateState {
          it.copy(pendingFingerprint = null, pendingServer = null)
        }
        client.reconnectNow()
        return Result().apply { ok = true }
      }

      override fun rejectServer(): Result {
        log.info { "Server certificate rejected, connection switched off" }
        connectionStateModel.updateState {
          it.copy(isEnabled = false, pendingFingerprint = null, pendingServer = null)
        }
        return Result().apply { ok = true }
      }
    }

  override fun onBind(intent: Intent?): IBinder {
    return binder
  }

  /** Event handler for `ClientEventBus` events. */
  private fun onClientEvent(event: ClientEvent) {
    when (event) {
      is ScreenEvent.AckReceived -> {
        connectionStateModel.updateState { it.copy(ackReceived = true) }
      }
      is ScreenEvent.Enter -> {
        connectionStateModel.updateState {
          it.copy(screen = it.screen.copy(isActive = true))
        }
      }
      is ScreenEvent.Leave -> {
        connectionStateModel.updateState {
          it.copy(screen = it.screen.copy(isActive = false))
        }
      }
      is ConnectionEvent.UntrustedServer -> {
        connectionStateModel.updateState {
          if (it.pendingFingerprint == event.fingerprint) it
          else
            it.copy(
              pendingFingerprint = event.fingerprint,
              pendingServer = "${event.address}:${event.port}",
            )
        }
      }
      is ConnectionEvent.ConnectFailed -> {
        connectionStateModel.updateState {
          it.copy(failureKind = event.reason.code, failedAttempts = event.consecutiveFailures)
        }
      }
      is ConnectionEvent -> {
        val connected = event is ConnectionEvent.Connected
        log.debug { "ConnectionEvent(connected=$connected)" }
        connectionStateModel.updateState {
          it.copy(
            isConnected = connected,
            ackReceived = if (connected) it.ackReceived else false,
            pendingFingerprint = if (connected) null else it.pendingFingerprint,
            pendingServer = if (connected) null else it.pendingServer,
            failureKind = if (connected) ConnectionFailure.NONE_CODE else it.failureKind,
            failedAttempts = if (connected) 0 else it.failedAttempts,
          )
        }
      }
      is LogRecordEvent,
      is ScreenEvent.SetClipboard,
      is MouseEvent,
      is KeyboardEvent -> {
        val bundle =
          Bundle().apply {
            putString("type", event.javaClass.name)
            putSerializable("message", event)
          }

        sendToClients { onMessage(bundle) }
      }
    }
  }

  /** Check if the client is enabled and update if necessary. */
  private fun checkEnabled() {
    val state = connectionStateModel.state.value

    if (state.isEnabled != client.isEnabled) {
      log.info {
        "ConnectionService setting Client.isEnabled=${state.isEnabled}"
      }
      client.setEnabled(state.isEnabled)
    }
  }

  /** Check if the client is connected and update if necessary. */
  private suspend fun connectionStateUpdateJobRunnable() {
    connectionStateModel.state.collect { state ->
      log.info { "CHANGED: ConnectionState(${state.toDebugString()})" }

      checkEnabled()
      sendToClients { onStateChanged(state) }

      val screen = state.screen
      client.setTarget(screen.toServerTarget())
    }
  }

  /**
   * Update the service configuration when preferences
   *
   * This is attached to the `appPrefsStore` flow and will be called
   */
  private val onAppPrefsChanged = FlowCollector { appPrefs: AppPrefs ->
    trustedFingerprints =
      appPrefs.trustedServerFingerprintsList.map { CertificateFingerprint.normalize(it) }.toSet()
    clipboardSyncEnabled = appPrefs.clipboardSyncEnabled
    appPrefs.logging?.let { logging ->
// TODO: Add toggle to settings to enable/disable forwarding
//      logging.forwardingEnabled
      AndroidForwardingLogger.setForwardingEnabled(true)
      val forwardingLevel =
        logging.forwardingLevel.uppercase().let { levelName ->
          Level.entries.find { level -> level.name == levelName } ?: Level.INFO
        }

      AndroidForwardingLogger.forwardingLevel = forwardingLevel

      log.info {
        "AppPrefs changed: forwardingLevel=${AndroidForwardingLogger.forwardingLevel}"
      }
    }
    connectionStateModel.updateScreenFromAppPrefs(appPrefs)
  }

  override fun onCreate() {
    super.onCreate()
    log.debug { "onCreate:${this::class.java.simpleName}" }

//    if (BuildConfig.DEBUG) {
//      android.os.Debug.waitForDebugger()
//    }

    connectionStateModel = ConnectionStateModel(this)
    // collect() never returns; its first emission already applies the stored preferences.
    serviceScope.launch { appPrefsStore.data.collect(onAppPrefsChanged) }

    ClientEventBus.on(clientEventListener)

    // H2: follow rotation / display changes while connected.
    runCatching {
      getSystemService(android.hardware.display.DisplayManager::class.java)
        .registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    connectionStateUpdateJob =
      serviceScope.launch { connectionStateUpdateJobRunnable() }

    try {
      getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
    } catch (err: Exception) {
      log.warn { "Network callback not available: ${err.message}" }
    }
    autoDiscoveryJob = serviceScope.launch { autoDiscoveryRunnable() }
  }

  private suspend fun autoDiscoveryRunnable() {
    delay(AUTO_DISCOVERY_INITIAL_DELAY_MS)

    var disconnectedSince = System.currentTimeMillis()
    // A subnet scan touches up to ~1000 hosts: back off exponentially while
    // nothing usable is found and start over when the network changes or a
    // connection succeeded (code review D15).
    var backoffMs = AUTO_DISCOVERY_MIN_BACKOFF_MS
    var nextScanAt = 0L
    while (currentCoroutineContext().isActive) {
      val state = connectionStateModel.state.value
      val currentServer = state.screen.server
      if (state.isConnected || networkChanged) {
        if (state.isConnected) disconnectedSince = System.currentTimeMillis()
        networkChanged = false
        backoffMs = AUTO_DISCOVERY_MIN_BACKOFF_MS
        nextScanAt = 0L
      }

      val hasServer =
        currentServer.address.isNotBlank() &&
          currentServer.address != SERVER_DEFAULT_ADDRESS
      val shouldDiscover =
        (hasServer || appPrefsStore.data.first().setupCompleted) &&
          state.isEnabled &&
          !state.isConnected &&
          currentServer.autoDiscover &&
          (!hasServer ||
            System.currentTimeMillis() - disconnectedSince >
              AUTO_DISCOVERY_RECONNECT_GRACE_MS) &&
          System.currentTimeMillis() >= nextScanAt

      if (shouldDiscover) {
        val servers =
          catch({ serverDiscovery.discover(currentServer.port) }) { err: Throwable ->
            log.warn { "Server discovery failed: ${err.message}" }
            emptyList()
          }

        // Only trusted (or, before setup, verified TLS) servers – see AutoConnectSelector.
        val best =
          AutoConnectSelector.select(
            servers,
            trustedFingerprints,
            hasServer,
            currentServer.address,
            currentServer.port,
          )

        if (best == null) {
          nextScanAt = System.currentTimeMillis() + backoffMs
          backoffMs = (backoffMs * 2).coerceAtMost(AUTO_DISCOVERY_MAX_BACKOFF_MS)
        } else {
          if (
            best.address != currentServer.address ||
              best.port != currentServer.port ||
              best.useTls != currentServer.useTls
          ) {
            log.info {
              "Auto connecting to ${best.displayName} (${best.address}:${best.port}, tls=${best.useTls})"
            }
            appPrefsStore.updateData { appPrefs ->
              appPrefs.copy {
                screen =
                  screen.copy {
                    server =
                      server.copy {
                        address = best.address
                        port = best.port
                        useTls = best.useTls
                      }
                  }
              }
            }
          }
          disconnectedSince = System.currentTimeMillis()
        }
      }

      delay(AUTO_DISCOVERY_TICK_MS)
    }
  }

  @Volatile private var networkChanged = false

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        networkChanged = true
      }

      override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        networkChanged = true
      }
    }

  /**
   * Send a message to all registered clients.
   *
   * @param fn The function to call on each client.
   */
  private fun sendToClients(fn: IConnectionServiceCallback.() -> Unit) {
    if (broadcastExecutor.isShutdown) return

    broadcastExecutor.submit {
      synchronized(broadcastLock) {
        try {
          val count = connectionCallbacks.beginBroadcast()
          try {
            for (i in 0 until count) {
              try {
                connectionCallbacks.getBroadcastItem(i).fn()
              } catch (e: Exception) {
                log.error(e) { "Error sending to client" }
              }
            }
          } finally {
            connectionCallbacks.finishBroadcast()
          }
        } catch (err: Exception) {
          log.error(err) { "Error sendingToClients" }
        }
      }
    }
  }

  private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
      if (displayId == android.view.Display.DEFAULT_DISPLAY && ::connectionStateModel.isInitialized) {
        connectionStateModel.updateScreenSize()
      }
    }
  }

  override fun onDestroy() {
    ClientEventBus.off(clientEventListener)
    runCatching { getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayListener) }

    synchronized(broadcastLock) {
      broadcastExecutor.shutdownNow()
      connectionCallbacks.kill()
    }

    client.dispose()

    connectionStateUpdateJob?.cancel()
    connectionStateUpdateJob = null

    autoDiscoveryJob?.cancel()
    autoDiscoveryJob = null
    try {
      getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
    } catch (_: Exception) {}

    serviceScope.cancel()

    super.onDestroy()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }
}
