package dev.blamspot.jcode.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration.KEYBOARD_NOKEYS
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.core.layout.WindowSizeClass

enum class QCodeWindowWidthClass {
    Compact,
    Medium,
    Expanded,
}

enum class QCodeWindowHeightClass {
    Compact,
    Medium,
    Expanded,
}

enum class QCodePosture {
    Flat,
    TableTop,
    Book,
}

data class QCodeWindowInfo(
    val widthClass: QCodeWindowWidthClass,
    val heightClass: QCodeWindowHeightClass,
    val posture: QCodePosture,
    val hasPhysicalKeyboard: Boolean,
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberQCodeWindowInfo(): State<QCodeWindowInfo> {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val adaptiveInfo = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasConfigurationKeyboard = configuration.keyboard != KEYBOARD_NOKEYS
    val inputManager = remember(context) { context.getSystemService(InputManager::class.java) }

    fun computeKeyboardPresent(): Boolean {
        val manager = inputManager ?: return hasConfigurationKeyboard
        if (hasConfigurationKeyboard) return true
        return manager.inputDeviceIds.any { deviceId ->
            val device = manager.getInputDevice(deviceId)
            device != null && !device.isVirtual && device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE
        }
    }

    var hasPhysicalKeyboard by remember(hasConfigurationKeyboard, inputManager) {
        mutableStateOf(computeKeyboardPresent())
    }

    DisposableEffect(inputManager, hasConfigurationKeyboard) {
        val manager = inputManager ?: return@DisposableEffect onDispose { }
        hasPhysicalKeyboard = computeKeyboardPresent()
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                hasPhysicalKeyboard = computeKeyboardPresent()
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                hasPhysicalKeyboard = computeKeyboardPresent()
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                hasPhysicalKeyboard = computeKeyboardPresent()
            }
        }
        manager.registerInputDeviceListener(listener, null)
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }

    return produceState(
        initialValue = QCodeWindowInfo(
            widthClass = adaptiveInfo.windowSizeClass.toQCodeWidthClass(),
            heightClass = adaptiveInfo.windowSizeClass.toQCodeHeightClass(),
            posture = QCodePosture.Flat,
            hasPhysicalKeyboard = hasPhysicalKeyboard,
        ),
        adaptiveInfo,
        activity,
        lifecycleOwner,
        hasPhysicalKeyboard,
    ) {
        if (activity == null) {
            value = value.copy(hasPhysicalKeyboard = hasPhysicalKeyboard)
            return@produceState
        }

        val tracker = WindowInfoTracker.getOrCreate(activity)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            tracker.windowLayoutInfo(activity).collect { layoutInfo ->
                val foldingFeature = layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                value = QCodeWindowInfo(
                    widthClass = adaptiveInfo.windowSizeClass.toQCodeWidthClass(),
                    heightClass = adaptiveInfo.windowSizeClass.toQCodeHeightClass(),
                    posture = foldingFeature.toQCodePosture(),
                    hasPhysicalKeyboard = hasPhysicalKeyboard,
                )
            }
        }
    }
}

private fun FoldingFeature?.toQCodePosture(): QCodePosture = when {
    this == null -> QCodePosture.Flat
    state != FoldingFeature.State.HALF_OPENED -> QCodePosture.Flat
    orientation == FoldingFeature.Orientation.HORIZONTAL -> QCodePosture.TableTop
    else -> QCodePosture.Book
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun WindowSizeClass.toQCodeWidthClass(): QCodeWindowWidthClass = when {
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> QCodeWindowWidthClass.Expanded
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> QCodeWindowWidthClass.Medium
    else -> QCodeWindowWidthClass.Compact
}

private fun WindowSizeClass.toQCodeHeightClass(): QCodeWindowHeightClass = when {
    isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND) -> QCodeWindowHeightClass.Expanded
    isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND) -> QCodeWindowHeightClass.Medium
    else -> QCodeWindowHeightClass.Compact
}
