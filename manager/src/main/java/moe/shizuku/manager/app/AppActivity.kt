package moe.shizuku.manager.app

import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.Color
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import moe.shizuku.manager.R
import rikka.core.res.isNight
import rikka.core.res.resolveColor
import rikka.material.app.MaterialActivity

abstract class AppActivity : MaterialActivity() {

    override fun computeUserThemeKey(): String {
        return ThemeHelper.getTheme(this) + ThemeHelper.isUsingSystemColor()
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(this), true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Remotes such as the MX3 Air Mouse register as a full QWERTY keyboard,
        // so their arrow keys arrive with source == SOURCE_KEYBOARD. TextView
        // swallows keyboard-sourced arrow keys whenever it has a movement method
        // (e.g. LinkMovementMethod on the home cards' descriptions), so focus
        // gets stuck there. Re-tag them as D-pad keys so focus navigation works
        // the same as with a regular TV remote.
        if (event.source == InputDevice.SOURCE_KEYBOARD && isArrowKey(event.keyCode)) {
            return super.dispatchKeyEvent(KeyEvent(event).apply { source = InputDevice.SOURCE_DPAD })
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isArrowKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
} 
