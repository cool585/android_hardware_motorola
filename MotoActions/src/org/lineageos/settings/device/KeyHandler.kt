/*
 * SPDX-FileCopyrightText: 2016 The CyanogenMod Project
 * SPDX-FileCopyrightText: 2017-2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.input.InputManager
import android.media.session.MediaSessionLegacyHelper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler

open class KeyHandler(protected val context: Context) : DeviceKeyHandler {

    private val powerManager: PowerManager?
        get() = context.getSystemService(PowerManager::class.java)
    private val keyguardManager: KeyguardManager?
        get() = context.getSystemService(KeyguardManager::class.java)
    private val vibrator: Vibrator?
        get() = context.getSystemService(Vibrator::class.java)
    private val cameraManager: CameraManager?
        get() = context.getSystemService(CameraManager::class.java)
    private val inputManager: InputManager?
        get() = context.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var torchEnabled = false
    private var rearCameraId: String? = null
    private var cameraInitialized = false

    init {
        runCatching {
            ensureCameraInit()
        }
    }

    private fun ensureCameraInit() {
        if (cameraInitialized) return
        val cm = cameraManager ?: return
        cameraInitialized = true
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == rearCameraId) {
                    torchEnabled = enabled
                }
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == rearCameraId) {
                    torchEnabled = false
                }
            }
        }, handler)

        runCatching {
            cm.cameraIdList.forEach { cameraId ->
                val characteristics = cm.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    rearCameraId = cameraId
                    return@forEach
                }
            }
        }
    }

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        val scanCode = event.scanCode

        if (scanCode != ASSISTANT_SCANCODE) {
            return event
        }

        // We only want ACTION_UP event; consume ACTION_DOWN
        if (event.action != KeyEvent.ACTION_UP) {
            return null
        }

        val action = Settings.System.getInt(
            context.contentResolver,
            ASSISTANT_KEY,
            ACTION_VOICE_ASSISTANT,
        )

        Log.d(TAG, "Assistant key triggered: scanCode=$scanCode, action=$action")

        if (action == ACTION_NONE) {
            return null
        }

        fireAction(action)
        return null
    }

    private fun fireAction(action: Int) {
        val keyguardLocked = keyguardManager?.inKeyguardRestrictedInputMode() ?: false

        when (action) {
            ACTION_HOME -> {
                if (!keyguardLocked) {
                    doHapticFeedback()
                    triggerVirtualKeypress(KeyEvent.KEYCODE_HOME)
                }
            }
            ACTION_POWER -> {
                doHapticFeedback()
                toggleScreenState()
            }
            ACTION_BACK -> {
                doHapticFeedback()
                triggerVirtualKeypress(KeyEvent.KEYCODE_BACK)
            }
            ACTION_RECENTS -> {
                if (!keyguardLocked) {
                    doHapticFeedback()
                    triggerVirtualKeypress(KeyEvent.KEYCODE_APP_SWITCH)
                }
            }
            ACTION_VOLUME_UP -> {
                doHapticFeedback()
                triggerVirtualKeypress(KeyEvent.KEYCODE_VOLUME_UP)
            }
            ACTION_VOLUME_DOWN -> {
                doHapticFeedback()
                triggerVirtualKeypress(KeyEvent.KEYCODE_VOLUME_DOWN)
            }
            ACTION_VOICE_ASSISTANT -> {
                if (!keyguardLocked) {
                    fireVoiceAssistant()
                }
            }
            ACTION_PLAY_PAUSE -> {
                doHapticFeedback()
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            ACTION_PREVIOUS_TRACK -> {
                doHapticFeedback()
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            ACTION_NEXT_TRACK -> {
                doHapticFeedback()
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            ACTION_FLASHLIGHT -> {
                vibrate(250)
                toggleFlashlight()
            }
            ACTION_CAMERA -> {
                vibrate(500)
                triggerCameraAction()
            }
            ACTION_SCREENSHOT -> {
                doHapticFeedback()
                triggerVirtualKeypress(KeyEvent.KEYCODE_SYSRQ)
            }
            ACTION_PIP -> {
                if (!keyguardLocked) {
                    doHapticFeedback()
                    goToPipMode()
                }
            }
            ACTION_LAST_APP -> {
                if (!keyguardLocked) {
                    doHapticFeedback()
                    switchToLastApp()
                }
            }
            else -> {
                Log.w(TAG, "Unknown assistant key action: $action")
            }
        }
    }

    private fun toggleScreenState() {
        val pm = powerManager ?: return
        if (pm.isInteractive) {
            pm.goToSleep(SystemClock.uptimeMillis())
        } else {
            pm.wakeUp(
                SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_GESTURE,
                TAG,
            )
        }
    }

    private fun triggerVirtualKeypress(keyCode: Int) {
        val im = inputManager ?: return
        val now = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN,
            keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_CLASS_BUTTON,
        )
        val upEvent = KeyEvent.changeAction(downEvent, KeyEvent.ACTION_UP)

        handler.postDelayed({
            im.injectInputEvent(downEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        }, 10)
        handler.postDelayed({
            im.injectInputEvent(upEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        }, 20)
    }

    private fun fireVoiceAssistant() {
        doHapticFeedback()
        val sm = context.getSystemService(SearchManager::class.java)
        val launched = runCatching {
            sm?.launchAssist(Bundle())
            true
        }.getOrDefault(false)

        if (!launched) {
            triggerVirtualKeypress(KeyEvent.KEYCODE_ASSIST)
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val success = runCatching {
            val helper = MediaSessionLegacyHelper.getHelper(context)
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            helper.sendMediaButtonEvent(down, true)
            helper.sendMediaButtonEvent(up, true)
            true
        }.getOrDefault(false)

        if (!success) {
            triggerVirtualKeypress(keyCode)
        }
    }

    private fun toggleFlashlight() {
        ensureCameraInit()
        val cm = cameraManager ?: return
        val id = rearCameraId ?: return
        val pm = powerManager ?: return
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:Torch")
        wakeLock.acquire(1000)
        runCatching {
            cm.setTorchMode(id, !torchEnabled)
            torchEnabled = !torchEnabled
        }
    }

    private fun triggerCameraAction() {
        val pm = powerManager ?: return
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$TAG:CameraWakeLock",
        )
        wl.acquire(500)

        if (keyguardManager?.inKeyguardRestrictedInputMode() == true) {
            launchSecureCamera()
        } else {
            launchCamera()
        }
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_FROM_BACKGROUND)
        }
        startActivitySafely(intent)
    }

    private fun launchSecureCamera() {
        val secureIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_FROM_BACKGROUND)
        }
        val normalIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_FROM_BACKGROUND)
        }
        val normalActivity = getBestActivityInfo(normalIntent)
        val secureActivity = getBestActivityInfo(secureIntent, normalActivity)
        if (secureActivity != null) {
            secureIntent.component = ComponentName(secureActivity.packageName, secureActivity.name)
        }
        startActivitySafely(secureIntent)
    }

    private fun getBestActivityInfo(
        intent: Intent,
        matchActivity: ActivityInfo? = null,
    ): ActivityInfo? {
        val pm = context.packageManager
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (activities.isEmpty()) return null
        if (matchActivity != null) {
            for (info in activities) {
                if (info.activityInfo.packageName == matchActivity.packageName) {
                    return info.activityInfo
                }
            }
        }
        return activities[0].activityInfo
    }

    private fun startActivitySafely(intent: Intent) {
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        runCatching {
            context.startActivityAsUser(intent, null, UserHandle.CURRENT)
        }.onFailure {
            runCatching {
                context.startActivity(intent)
            }
        }
    }

    private fun goToPipMode() {
        triggerVirtualKeypress(KeyEvent.KEYCODE_WINDOW)
    }

    private fun switchToLastApp() {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val tasks = runCatching { am.getRunningTasks(5) }.getOrNull() ?: return
        val defaultHome = resolveCurrentLauncherPackage()
        for (i in 1 until tasks.size) {
            val task = tasks[i]
            val pkg = task.topActivity?.packageName
            if (pkg != null && pkg != defaultHome && pkg != context.packageName && pkg != "com.android.systemui") {
                am.moveTaskToFront(task.taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
                break
            }
        }
    }

    private fun resolveCurrentLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    private fun doHapticFeedback() {
        vibrator?.let {
            val effect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            it.vibrate(effect, VIBRATION_ATTRIBUTES)
        }
    }

    private fun vibrate(duration: Long) {
        vibrator?.let {
            val effect = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
            it.vibrate(effect, VIBRATION_ATTRIBUTES)
        }
    }

    companion object {
        private const val TAG = "MotoActionsKeyHandler"

        const val ASSISTANT_SCANCODE = 217
        const val ASSISTANT_KEY = "assistant_button"

        const val ACTION_NONE = 0
        const val ACTION_HOME = 100
        const val ACTION_POWER = 101
        const val ACTION_BACK = 102
        const val ACTION_RECENTS = 103
        const val ACTION_VOLUME_UP = 104
        const val ACTION_VOLUME_DOWN = 105
        const val ACTION_VOICE_ASSISTANT = 106
        const val ACTION_PLAY_PAUSE = 107
        const val ACTION_PREVIOUS_TRACK = 108
        const val ACTION_NEXT_TRACK = 109
        const val ACTION_FLASHLIGHT = 110
        const val ACTION_CAMERA = 111
        const val ACTION_SCREENSHOT = 112
        const val ACTION_PIP = 120
        const val ACTION_LAST_APP = 121

        private val VIBRATION_ATTRIBUTES = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_TOUCH)
            .build()
    }
}
