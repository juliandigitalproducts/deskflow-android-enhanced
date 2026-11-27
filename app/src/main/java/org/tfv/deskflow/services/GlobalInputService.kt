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

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import arrow.core.raise.catch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.math.hypot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.tfv.deskflow.R
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.components.AndroidPrimaryClipSource
import org.tfv.deskflow.components.ClipboardShareFilter
import org.tfv.deskflow.components.GlobalKeyboardManager
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.ext.getScreenSize
import org.tfv.deskflow.ext.sendServiceConnectionEvent
import org.tfv.deskflow.services.input.ScrollController
import org.tfv.deskflow.services.input.ScrollMode
import org.tfv.deskflow.services.input.VirtualFinger

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@SuppressLint("ServiceCast", "NewApi")
class GlobalInputService : AccessibilityService() {

  private var pickerShownForPackage: String? = null

  /** Client responsible for communicating with the connection service. */
  private lateinit var serviceClient: ConnectionServiceClient

  /**
   * Keyboard manager for handling keyboard events, calculating state and
   * triggering actions.
   */
  private lateinit var keyboardManager: GlobalKeyboardManager

  private val clipboard by lazy {
    getSystemService(ClipboardManager::class.java)
  }

  private val imeManager by lazy {
    getSystemService(InputMethodManager::class.java)
  }

  private val notificationManager by lazy {
    getSystemService(NotificationManager::class.java)
  }

  private val activePackageName: String?
    get() = rootInActiveWindow?.packageName?.toString()

  private val keyboardWindowInfo: AccessibilityWindowInfo?
    get() =
      windows.find { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

  private val isKeyboardOpened: Boolean
    get() = keyboardWindowInfo != null

  /**
   * Handler for posting global input actions to the main thread. This is used
   * to ensure that gestures are dispatched on the main thread.
   */
  private val globalInputHandler = Handler(Looper.getMainLooper())

  /**
   * Layout parameters for the mouse pointer view. This is used to position the
   * mouse pointer on the screen.
   */
  private lateinit var mousePointerLayout: WindowManager.LayoutParams

  /**
   * View for the mouse pointer. This is the view that will be displayed on the
   * screen to represent the mouse pointer.
   */
  private lateinit var mousePointerView: View

  /**
   * Flag to indicate if the mouse pointer is currently visible. This is used to
   * control the visibility of the mouse pointer view.
   */
  @Volatile private var mousePointerVisible = false

  private val keyboardWasOpen = AtomicBoolean(false)

  /** WindowManager instance for managing the mouse pointer view. */
  private lateinit var windowManager: WindowManager

  private val finger by lazy {
    VirtualFinger(this, globalInputHandler, ::screenBounds)
  }

  private val scrollController by lazy {
    ScrollController(
      this,
      finger,
      globalInputHandler,
      pointerProvider = {
        PointF(mousePointerLayout.x.toFloat(), mousePointerLayout.y.toFloat())
      },
      boundsProvider = ::screenBounds,
      density = resources.displayMetrics.density,
      nodeScroller = ::scrollNodeAt,
    )
  }

  private var leftButtonPressed = false
  private var leftButtonDragging = false
  private val leftButtonPressPoint = PointF()

  /** Releases a held left button / virtual finger and pressed modifiers (see H3). */
  private fun resetInputState() {
    globalInputHandler.removeCallbacks(longPressRunnable)
    if (leftButtonPressed || leftButtonDragging) {
      runCatching { finger.cancel() }
    }
    leftButtonPressed = false
    leftButtonDragging = false
    runCatching { scrollController.reset() }
    if (::keyboardManager.isInitialized) keyboardManager.resetModifiers()
  }

  private val longPressRunnable = Runnable {
    if (leftButtonPressed && !leftButtonDragging) {
      startDrag()
      finger.hold()
    }
  }

  private fun screenBounds(): PointF {
    val screenSize = getScreenSize()
    return PointF(
      screenSize.px.width.toFloat(),
      screenSize.px.height.toFloat(),
    )
  }

  private fun scrollNodeAt(x: Float, y: Float, dirX: Int, dirY: Int) {
    for (window in windows) {
      var node =
        window.root?.let { findSmallestNodeAtPoint(it, x.toInt(), y.toInt()) }
      while (node != null) {
        if (node.isScrollable && performScrollAction(node, dirX, dirY)) return
        node = node.parent
      }
    }
  }

  private fun performScrollAction(
    node: AccessibilityNodeInfo,
    dirX: Int,
    dirY: Int,
  ): Boolean {
    val (directional, fallback) =
      when {
        dirY > 0 ->
          AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP to
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
        dirY < 0 ->
          AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN to
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
        dirX > 0 ->
          AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT to
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
        else ->
          AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT to
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
      }
    return node.performAction(directional.id) ||
      node.performAction(fallback.id)
  }

  private val serviceScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Clipboard sharing with the computer is opt-in (Settings). */
  @Volatile private var clipboardSyncEnabled = false

  private fun createNotificationChannel() {

    val name =
      resources.getText(R.string.global_input_service_notification_channel_name)
    val desc =
      resources.getText(
        R.string.global_input_service_notification_channel_description
      )
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val chan =
      NotificationChannel(CHANNEL_ID, name, importance).apply {
        description = desc.toString()
      }
    notificationManager.createNotificationChannel(chan)
  }

  private fun sendStatusNotification(
    text: String,
    customizer: (NotificationCompat.Builder.() -> Unit)? = null,
  ) {
    if (
      ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS,
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      log.warn {
        "POST_NOTIFICATIONS permission not granted, cannot send notification."
      }
      return
    }
    val notificationBuilder =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.app_logo_glyph) // own app logo (art/brand/)
        .setContentTitle(
          resources.getText(
            R.string.global_input_service_notification_channel_name
          )
        )
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
    if (customizer != null) {
      customizer(notificationBuilder)
    }

    val notification = notificationBuilder.build()
    // TODO: Add action to open keyboard settings
    NotificationManagerCompat.from(this)
      .notify(NOTIF_IME_NOT_SETUP_ID, notification)
  }

  private fun isDeskflowKeyboardActive(
    imeInfo: InputMethodInfo? = imeManager.currentInputMethodInfo
  ): Boolean {
    return imeInfo?.let { current -> deskflowImeInfo?.id == current.id }
      ?: false
  }

  @SuppressLint("NewApi")
  private fun checkIMESetup() {
    val kbOpen = isKeyboardOpened
    val kbWasOpen = keyboardWasOpen.load()
    if (!kbOpen) {
      keyboardWasOpen.store(false)
      return
    }

    if (kbWasOpen) return

    if (!serviceClient.state.isConnected) return

    keyboardWasOpen.store(true)

    val imeInfo = imeManager.currentInputMethodInfo
    if (imeInfo != null) {
      log.debug { "Current IME: ${imeInfo.packageName}" }
      when {
        isDeskflowKeyboardActive(imeInfo) -> {
          log.debug { "Current IME is deskflow, no action needed." }
        }
        !isDeskflowImeEnabled -> {
          log.warn { "IME is not enabled, showing notification." }
          sendStatusNotification(
            resources.getString(
              R.string.global_input_service_notification_ime_not_setup
            )
          ) {
            setContentIntent(
              PendingIntent.getActivity(
                this@GlobalInputService,
                0,
                Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE,
              )
            )
          }
        }
        with(activePackageName) {
          this == null || pickerShownForPackage == this
        } -> {
          log.debug {
            "IME picker was already shown for package $pickerShownForPackage"
          }
        }
        else -> {
          log.debug {
            "IME is enabled, but not active. Previous picker was shown for package $pickerShownForPackage"
          }

          pickerShownForPackage = activePackageName
          val imeId = deskflowImeInfo?.id
          if (
            imeId != null && softKeyboardController.switchToInputMethod(imeId)
          ) {
            log.debug { "softKeyboardController set IME to $imeId" }
            return
          }

          imeManager.showInputMethodPicker()
        }
      }
    } else {
      log.warn { "No current IME found." }
    }
  }

  @SuppressLint("RtlHardcoded")
  override fun onCreate() {
    super.onCreate()
    log.debug { "onCreate:${GlobalInputService::class.simpleName}" }

    createNotificationChannel()

    keyboardManager = GlobalKeyboardManager(this)

    serviceScope.launch {
      appPrefsStore.data.collect { appPrefs ->
        val input = appPrefs.input
        scrollController.invertY = input.scrollInvert
        scrollController.invertX = input.scrollInvertX
        scrollController.mode = ScrollMode.fromId(input.scrollMode)
        scrollController.smoothing =
          if (input.hasScrollSmoothing()) input.scrollSmoothing / 100f
          else ScrollController.DEFAULT_SMOOTHING
        scrollController.speed =
          if (input.scrollSpeedPercent <= 0) 1f
          else input.scrollSpeedPercent / 100f
        clipboardSyncEnabled = appPrefs.clipboardSyncEnabled
      }
    }
    serviceScope.launch {
      keyboardManager.actionFlow.collect { action ->
        log.debug { "Triggered Action: ${action.label}(${action.actionId})" }
        when (action.actionId) {
          GLOBAL_ACTION_DPAD_CENTER -> {
            clickFocused()
          }

          else -> {
            performGlobalAction(action.actionId)
          }
        }
      }
    }

    // H3: leaving the screen or losing the connection releases everything that is still "pressed".
    serviceScope.launch {
      while (!::serviceClient.isInitialized) kotlinx.coroutines.delay(50)
      serviceClient.stateFlow
        .map { it.isConnected && it.screen.isActive }
        .distinctUntilChanged()
        .collect { active -> if (!active) globalInputHandler.post { resetInputState() } }
    }

    serviceClient =
      ConnectionServiceClient(this) { event ->
        globalInputHandler.post {
          try {
            when (event) {
              is MouseEvent -> {
                onMouseEvent(event)
              }

              is KeyboardEvent -> {
                onKeyboardEvent(event)
              }

              is ScreenEvent.SetClipboard -> {
                if (!clipboardSyncEnabled) {
                  log.debug { "SetClipboard ignored: clipboard sharing is off" }
                  return@post
                }
                val data = event.data
                log.debug { "SetClipboard(variantCount=${data.variants.size})" }
                val knownFormats =
                  arrayOf(
                    ClipboardData.Format.Text // ClipboardData.Format.Bitmap
                  )

                for (format in knownFormats) {
                  val variant = data.variants[format] ?: continue

                  // TODO: Implement `Converter` concept
                  val clipData =
                    when (format) {
                      ClipboardData.Format.Text -> {
                        log.debug { "SetClipboard: Text(${variant.data.size})" }
                        val text = String(variant.data, Charsets.UTF_8)
                        ClipData.newPlainText(SERVER_CLIP_LABEL, text)
                      }

                      else -> {
                        log.warn {
                          "SetClipboard: No converter for format $format"
                        }
                        continue
                      }
                    }

                  log.debug { "Clipboard variant ready (${variant.data.size} bytes)" }
                  clipboard.setPrimaryClip(clipData)
                  break
                }
              }

              else -> {}
            }
          } catch (err: Exception) {
            log.error(err) {
              "Error running on globalInputHandler: ${err.message}"
            }
          }
        }
      }

    mousePointerView = View.inflate(baseContext, R.layout.mouse_pointer, null)
    mousePointerLayout =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      )

    mousePointerLayout.layoutInDisplayCutoutMode =
      LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    mousePointerLayout.gravity = Gravity.TOP or Gravity.LEFT
    mousePointerLayout.x = 200
    mousePointerLayout.y = 200

    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    startService(Intent(applicationContext, ConnectionService::class.java))
    serviceClient.bind()
  }

  /**
   * Handle keyboard events from the client. This is where we process keyboard
   * events and pass them to the keyboard manager.
   * > Example: Used for handling key presses in the global input service.
   */
  private fun onKeyboardEvent(event: KeyboardEvent) {
    log.debug { "onKeyboardEvent(type=${event.type})" }
    keyboardManager.process(event)
  }

  /**
   * Called when the service is destroyed. This is where we clean up resources
   * and remove the mouse pointer view.
   */
  override fun onDestroy() {
    serviceScope.cancel()
    try {
      clipboard.removePrimaryClipChangedListener(onClipboardChanged)
    } catch (_: Exception) {}
    keyboardManager.dispose()
    globalInputHandler.removeCallbacksAndMessages(null)
    serviceClient.unbind()
    try {
      if (mousePointerView.isAttachedToWindow || mousePointerView.parent != null) {
        windowManager.removeView(mousePointerView)
      }
    } catch (_: Exception) {}
    super.onDestroy()
  }

  /**
   * Perform a click on the closest node to the mouse pointer. This is used to
   * simulate a mouse click on the screen.
   * > Example: Used for clicking on buttons or input fields in the global input
   * > service.
   */
  @Suppress("unused")
  private fun clickClosestNodeToPointer() {
    val nodeInfo = rootInActiveWindow ?: return
    val nearestNodeToMouse: AccessibilityNodeInfo? =
      findSmallestNodeAtPoint(
        nodeInfo,
        mousePointerLayout.x,
        mousePointerLayout.y,
      )
    if (nearestNodeToMouse != null) {
      logNodeHierarchy(nearestNodeToMouse, 0)
      nearestNodeToMouse.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
  }

  /**
   * Perform a click gesture at the current mouse pointer position. This is used
   * to simulate a mouse click on the screen.
   * > Example: Used for clicking on buttons or input fields in the global input
   * > service.
   */
  private fun clickGesture(
    x: Float = mousePointerLayout.x.toFloat(),
    y: Float = mousePointerLayout.y.toFloat(),
  ) {

    log.debug { "Click gesture at [$x, $y]" }

    val path = Path().apply { moveTo(x, y) }
    val gesture =
      GestureDescription.Builder()
        .addStroke(StrokeDescription(path, 0, 100))
        .build()
    dispatchGesture(gesture, null, null)
  }

  /**
   * Move the mouse pointer to the specified coordinates. This is used to update
   * the position of the mouse pointer on the screen.
   * > Example: Used for mouse movement in the global input service.
   */
  private fun moveMousePointer(x: Int, y: Int) {
    if (!mousePointerVisible) return

    val screenSize = getScreenSize()
    // N2: relative moves must not push the pointer off screen (gestures with negative coordinates fail).
    val cx = x.coerceIn(0, (screenSize.px.width - 1).coerceAtLeast(0))
    val cy = y.coerceIn(0, (screenSize.px.height - 1).coerceAtLeast(0))
    log.debug {
      "Cursor move to [${cx}, ${cy}] with size(${screenSize.px.width},${screenSize.px.height})"
    }
    mousePointerLayout.x = cx
    mousePointerLayout.y = cy

    windowManager.updateViewLayout(mousePointerView, mousePointerLayout)
  }

  /**
   * Move the cursor to the specified coordinates. This is used to move the
   * mouse pointer to a specific location on the screen.
   * > Example: Used for mouse movement in the global input service.
   */
  private fun onMouseEvent(event: MouseEvent) {
    when (event.type) {
      MouseEvent.Type.Move -> {
        moveMousePointer(event.x, event.y)
        onPointerMovedWhilePressed()
      }

      MouseEvent.Type.MoveRelative -> {
        moveMousePointer(
          mousePointerLayout.x + event.x,
          mousePointerLayout.y + event.y,
        )
        onPointerMovedWhilePressed()
      }

      MouseEvent.Type.Down -> {
        log.debug { "Down (button=${event.id})" }
        if (event.id == BUTTON_LEFT) {
          scrollController.reset()
          leftButtonPressed = true
          leftButtonDragging = false
          leftButtonPressPoint.set(
            mousePointerLayout.x.toFloat(),
            mousePointerLayout.y.toFloat(),
          )
          globalInputHandler.removeCallbacks(longPressRunnable)
          globalInputHandler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
        }
      }

      MouseEvent.Type.Up -> {
        log.debug { "Up (button=${event.id})" }
        when (event.id) {
          BUTTON_LEFT -> {
            globalInputHandler.removeCallbacks(longPressRunnable)
            val wasDragging = leftButtonDragging
            leftButtonPressed = false
            leftButtonDragging = false
            if (wasDragging) {
              finger.up()
            } else {
              clickGesture()
            }
          }

          BUTTON_RIGHT -> longPressGesture()

          BUTTON_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)

          else -> {}
        }
      }

      MouseEvent.Type.Wheel -> {
        log.debug { "Wheel [${event.x}, ${event.y}]" }
        if (leftButtonPressed) return
        scrollController.onWheel(event.x, event.y)
      }
    }
  }

  private fun onPointerMovedWhilePressed() {
    if (!leftButtonPressed) return

    val x = mousePointerLayout.x.toFloat()
    val y = mousePointerLayout.y.toFloat()
    if (!leftButtonDragging) {
      val distance =
        hypot(x - leftButtonPressPoint.x, y - leftButtonPressPoint.y)
      if (distance < DRAG_SLOP_PX) return
      globalInputHandler.removeCallbacks(longPressRunnable)
      startDrag()
    }
    finger.moveTo(x, y)
  }

  private fun startDrag() {
    leftButtonDragging = true
    scrollController.reset()
    finger.onLifted = null
    finger.down(leftButtonPressPoint.x, leftButtonPressPoint.y)
  }

  private fun longPressGesture() {
    scrollController.reset()

    val path =
      Path().apply {
        moveTo(mousePointerLayout.x.toFloat(), mousePointerLayout.y.toFloat())
      }
    val gesture =
      GestureDescription.Builder()
        .addStroke(StrokeDescription(path, 0, LONG_PRESS_GESTURE_MS))
        .build()
    dispatchGesture(gesture, null, null)
  }

  /** Set up the pointer view and add it to the window manager. */
  private fun setupMousePointer() {
    // TYPE_ACCESSIBILITY_OVERLAY needs no "draw over other apps" permission.
    if (!mousePointerVisible) {
      windowManager.addView(mousePointerView, mousePointerLayout)
      mousePointerVisible = true
    }
  }

  /**
   * Called when the service is started. This is where we set the service to be
   * sticky and return START_STICKY.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }

  val deskflowImeInfo: InputMethodInfo?
    get() = imeManager.inputMethodList.find { it.packageName == packageName }

  val isDeskflowImeEnabled: Boolean
    get() =
      imeManager.enabledInputMethodList.any { it.id == deskflowImeInfo?.id }

  private val primaryClipSource by lazy {
    AndroidPrimaryClipSource(this, { clipboard.primaryClipDescription }, { clipboard.primaryClip })
  }

  private val onClipboardChanged =
    object : ClipboardManager.OnPrimaryClipChangedListener {
      override fun onPrimaryClipChanged() {
        catch({
          // DSK-R11: the switch, label, sensitive flag and MIME types are checked before the content is read.
          val result = ClipboardShareFilter.evaluate(clipboardSyncEnabled, SERVER_CLIP_LABEL, primaryClipSource)
          val text =
            when (result) {
              is ClipboardShareFilter.Result.Share -> result.text
              is ClipboardShareFilter.Result.Skip -> {
                if (result.reason != ClipboardShareFilter.SkipReason.SHARING_OFF) {
                  log.debug { "Clip not sent to the server (${result.reason})" }
                }
                return@catch
              }
            }
          val clipboardData =
            ClipboardData(
              0,
              0,
              mapOf(
                ClipboardData.Format.Text to
                  ClipboardData.Variant(
                    ClipboardData.Format.Text,
                    text.toByteArray(),
                  )
              ),
            )
          serviceClient.setClipboardData(
            Bundle().apply { putSerializable("clipboardData", clipboardData) }
          )
        }) { err: Throwable ->
          log.error(err) { "unable to update clipboard" }
        }
      }
    }

  /**
   * Called when the service is connected to the system. This is where we set up
   * the cursor and fetch the home packages.
   */
  override fun onServiceConnected() {
    super.onServiceConnected()

    val imeId = deskflowImeInfo
    Log.i(TAG, "imeId=$imeId,imeEnabled=$isDeskflowImeEnabled")
    clipboard.addPrimaryClipChangedListener(this.onClipboardChanged)

    setupMousePointer()

    sendServiceConnectionEvent<GlobalInputService>()
  }

  /**
   * All accessibility events are received here; we pay little attention to
   * them.
   */
  private var lastImeCheck = 0L

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    log.debug { "onAccessibilityEvent: ${event?.eventType}" }
    //
    when (event?.eventType) {
      // N3: window changes decide which IME is needed; content changes fire constantly (every redraw), so they
      // only trigger a check every few seconds.
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
        lastImeCheck = System.currentTimeMillis()
        checkIMESetup()
      }
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
        val now = System.currentTimeMillis()
        if (now - lastImeCheck >= IME_CHECK_INTERVAL_MS) {
          lastImeCheck = now
          checkIMESetup()
        }
      }
      else -> {
        log.debug { "Ignored event: ${event?.eventType}" }
      }
    }
  }

  override fun onInterrupt() {
    // Required override.
  }

  /**
   * Click the currently focused node. This is useful for clicking on input
   * fields or buttons that are currently focused.
   * > Example: It's used for the DPAD_CENTER action in the system actions.
   */
  private fun clickFocused() {
    val focusedNode = findFocus(FOCUS_INPUT)
    // val focusedNode =
    // rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    if (focusedNode != null) {
      logNodeHierarchy(focusedNode, 0)
      focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } else {
      log.warn { "No focused node found to click" }
    }
  }

  /// **
  // * Example: continuously move pointer in a circle.
  // * Call this from onServiceConnected or via a button in your overlay.
  // */
  // fun spinPointer(centerX: Int, centerY: Int, radius: Int, steps: Int = 36,
  // intervalMs: Long = 100) {
  //    for (i in 0 until steps) {
  //        uiHandler.postDelayed({
  //            val theta = 2 * Math.PI * i / steps
  //            val x = centerX + (radius * Math.cos(theta)).toInt()
  //            val y = centerY + (radius * Math.sin(theta)).toInt()
  //            movePointer(centerX, centerY, x - centerX, y - centerY)
  //        }, i * intervalMs)
  //    }
  // }

  companion object {
    /** Label of clips set from the server (used to recognise them, see onClipboardChanged). */
    const val SERVER_CLIP_LABEL = "deskflow_clipboard"
    private const val IME_CHECK_INTERVAL_MS = 3_000L
    private const val CHANNEL_ID = "deskflow_service_channel"
    private const val NOTIF_IME_NOT_SETUP_ID = 1

    private const val BUTTON_LEFT = 1u
    private const val BUTTON_RIGHT = 3u
    private const val BUTTON_BACK = 4u
    private const val DRAG_SLOP_PX = 12f
    private const val LONG_PRESS_DELAY_MS = 300L
    private const val LONG_PRESS_GESTURE_MS = 700L

    private val TAG = GlobalInputService::class.java.simpleName
    private val log =
      KLoggingManager.logger(GlobalInputService::class.java.simpleName)

    private fun logNodeHierarchy(nodeInfo: AccessibilityNodeInfo, depth: Int) {
      val bounds = Rect()
      nodeInfo.getBoundsInScreen(bounds)

      val sb = StringBuilder()
      if (depth > 0) {
        (0..<depth).forEach { _ -> sb.append("  ") }
        sb.append("\u2514 ")
      }
      sb.append(nodeInfo.className)
      sb.append(" (" + nodeInfo.childCount + ")")
      sb.append(" $bounds")
      if (nodeInfo.getText() != null) {
        sb.append(" - \"" + nodeInfo.getText() + "\"")
      }
      log.trace { sb.toString() }

      for (i in 0..<nodeInfo.childCount) {
        val childNode = nodeInfo.getChild(i)
        if (childNode != null) {
          logNodeHierarchy(childNode, depth + 1)
        }
      }
    }

    private fun findSmallestNodeAtPoint(
      sourceNode: AccessibilityNodeInfo,
      x: Int,
      y: Int,
    ): AccessibilityNodeInfo? {
      val bounds = Rect()
      sourceNode.getBoundsInScreen(bounds)

      if (!bounds.contains(x, y)) {
        return null
      }

      for (i in 0..<sourceNode.childCount) {
        val child = sourceNode.getChild(i) ?: continue
        val nearestSmaller = findSmallestNodeAtPoint(child, x, y)
        if (nearestSmaller != null) {
          return nearestSmaller
        }
      }
      return sourceNode
    }
  }
}
