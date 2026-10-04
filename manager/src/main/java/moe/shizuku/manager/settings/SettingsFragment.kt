package moe.shizuku.manager.settings

import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.preference.Preference.SummaryProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.Keys.*
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.app.SnackbarHelper
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.receiver.NotifCancelReceiver
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.core.util.ResourceUtils
import rikka.html.text.HtmlCompat
import rikka.material.app.LocaleDelegate
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.manager.ShizukuLocales
import java.util.*

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var tcpModePreference: TwoStatePreference
    private lateinit var tcpPortPreference: EditTextPreference
    private lateinit var languagePreference: ListPreference
    private lateinit var translationPreference: Preference
    private lateinit var translationContributorsPreference: Preference
    private lateinit var nightModePreference: IntegerSimpleMenuPreference
    private lateinit var blackNightThemePreference: TwoStatePreference
    private lateinit var useSystemColorPreference: TwoStatePreference
    private lateinit var updateModePreference: IntegerSimpleMenuPreference
    private lateinit var helpPreference: Preference
    private lateinit var reportBugPreference: Preference
    private lateinit var legacyPairingPreference: TwoStatePreference
    private lateinit var advancedCategory: PreferenceCategory

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isRunning()) {
            tcpModePreference.icon = maybeGetRestartIcon(KEY_TCP_MODE)
            tcpPortPreference.icon = maybeGetRestartIcon(KEY_TCP_PORT)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        tcpModePreference = findPreference(KEY_TCP_MODE)!!
        tcpPortPreference = findPreference(KEY_TCP_PORT)!!
        languagePreference = findPreference(KEY_LANGUAGE)!!
        translationPreference = findPreference(KEY_TRANSLATION)!!
        translationContributorsPreference = findPreference(KEY_TRANSLATION_CONTRIBUTORS)!!
        nightModePreference = findPreference(KEY_NIGHT_MODE)!!
        blackNightThemePreference = findPreference(KEY_BLACK_NIGHT_THEME)!!
        useSystemColorPreference = findPreference(KEY_USE_SYSTEM_COLOR)!!
        updateModePreference = findPreference(KEY_UPDATE_MODE)!!
        helpPreference = findPreference(KEY_HELP)!!
        reportBugPreference = findPreference(KEY_REPORT_BUG)!!
        legacyPairingPreference = findPreference(KEY_LEGACY_PAIRING)!!
        advancedCategory = findPreference(KEY_CATEGORY_ADVANCED)!!

        tcpModePreference.apply {
            if (EnvironmentUtils.isTlsSupported()) {
                summary = context.getString(R.string.settings_tcp_mode_summary)
                icon = maybeGetRestartIcon(KEY_TCP_MODE)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue is Boolean) {
                        val applyChange: () -> Unit = {
                            ShizukuSettings.setTcpMode(newValue)
                            isChecked = newValue
                            isEnabled = true
                            summary = context.getString(R.string.settings_tcp_mode_summary)
                            icon = maybeGetRestartIcon(KEY_TCP_MODE)
                            tcpPortPreference.isVisible = newValue
                        }
                        
                        if (!newValue && !ShizukuStateMachine.isRunning() && needsRestart(KEY_TCP_MODE, newValue)) {
                            promptStopTcp { applyChange() }
                        } else maybePromptRestart (KEY_TCP_MODE, newValue) { applyChange() }
                    }
                    false
                }
            } else if (EnvironmentUtils.isTelevision()) {
                isEnabled = false
                isChecked = true
            } else {
                isVisible = false
            }
        }

        tcpPortPreference.apply {
            isVisible = tcpModePreference.isVisible && tcpModePreference.isChecked
            icon = maybeGetRestartIcon(KEY_TCP_PORT)

            setOnBindEditTextListener { editText ->
                editText.hint = context.getString(R.string.settings_tcp_port_hint)
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }

            summaryProvider = SummaryProvider<EditTextPreference> { pref ->
                val text = pref.text
                if (text.isNullOrEmpty()) context.getString(R.string.settings_tcp_port_default) else text
            }

            setOnPreferenceChangeListener { _, newValue ->
                val port = (newValue as? String)?.toIntOrNull()
                if (port == null || port in 1..65535) {
                    val applyChange: () -> Unit = {
                        ShizukuSettings.setTcpPort(port)
                        text = port?.toString()
                        icon = maybeGetRestartIcon(KEY_TCP_PORT)
                    }
                    maybePromptRestart (KEY_TCP_PORT, port ?: 5555) { applyChange() }
                } else {
                    SnackbarHelper.show(context, requireView(), context.getString(R.string.snackbar_invalid_port))
                }
                false
            }
        }

        languagePreference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                val locale: Locale = if ("SYSTEM" == newValue) {
                    LocaleDelegate.systemLocale
                } else {
                    Locale.forLanguageTag(newValue)
                }
                LocaleDelegate.defaultLocale = locale
                activity?.recreate()
            }
            true
        }

        setupLocalePreference(languagePreference)

        nightModePreference.apply {
            value = ShizukuSettings.getNightMode()
            setOnPreferenceChangeListener { _, value ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        activity?.recreate()
                    }
                }
                true
            }
        }

        blackNightThemePreference.apply {
            if (ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
                isChecked = ThemeHelper.isBlackNightTheme(context)
                setOnPreferenceChangeListener { _, _ ->
                    if (ResourceUtils.isNightMode(context.resources.configuration))
                        activity?.recreate()
                    true
                }
            } else isVisible = false
        }

        useSystemColorPreference.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                isChecked = ThemeHelper.isUsingSystemColor()
                setOnPreferenceChangeListener { _, value ->
                    if (value is Boolean) {
                        if (ThemeHelper.isUsingSystemColor() != value)
                            activity?.recreate()
                    }
                    true
                }
            } else isVisible = false
        }

        translationPreference.apply {
            summary = context.getString(R.string.settings_translation_summary, context.getString(R.string.app_name))
            setOnPreferenceClickListener {
                CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.translation_url))
                true
            }
        }

        translationContributorsPreference.apply {
            val contributors = context.getString(R.string.translation_contributors).toHtml().toString()
            if (contributors.isNotBlank()) {
                summary = contributors
            } else isVisible = false
        }

        updateModePreference.value = ShizukuSettings.getUpdateMode()

        helpPreference.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.help_url))
            true
        }

        reportBugPreference.setOnPreferenceClickListener {
            BugReportDialog().show(parentFragmentManager, "BugReportDialog")
            true
        }

        legacyPairingPreference.apply {
            isVisible = !EnvironmentUtils.isTelevision()
        }

        advancedCategory.isVisible = legacyPairingPreference.isVisible
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onPause() {
        ShizukuStateMachine.removeListener(stateListener)
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val systemBarsInsets = insets.getInsets(Type.systemBars() or Type.displayCutout())
            recyclerView.addItemSpacing(
                left = systemBarsInsets.left.toFloat(),
                right = systemBarsInsets.right.toFloat()
            )
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                systemBarsInsets.bottom
            )
            insets
        }

        recyclerView.fixEdgeEffect()

        return recyclerView
    }

    private fun needsRestart(setting: String, newValue: Any? = null): Boolean {
        val currentPort = EnvironmentUtils.getAdbTcpPort()
        return when (setting) {
            KEY_TCP_MODE -> {
                val newMode = newValue as? Boolean ?: ShizukuSettings.getTcpMode()
                (currentPort > 0) != newMode
            }
            KEY_TCP_PORT -> {
                val newPort = newValue as? Int ?: ShizukuSettings.getTcpPort()
                (currentPort > 0) && (currentPort != newPort)
            }
            else -> false
        }
    }

    private fun maybeGetRestartIcon(setting: String): Drawable? {
        val context = requireContext()
        if (!needsRestart(setting)) return null
        
        val icon = context.getDrawable(R.drawable.ic_server_restart)
        return tint(icon)
    }

    private fun tint(icon: Drawable?): Drawable? {
        val context = requireContext()
        val tintColor = TypedValue()
        context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tintColor, true)
        icon?.mutate()?.setTint(tintColor.data)
        return icon
    }

    private fun promptStopTcp (applyChange: () -> Unit) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(android.R.string.dialog_alert_title)
            .setMessage(context.getString(R.string.settings_tcp_mode_dialog_close_port))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    tcpModePreference.apply {
                        isEnabled = false
                        summary = context.getString(R.string.settings_tcp_mode_closing_port)
                    }
                    AdbStarter.stopTcp(context, EnvironmentUtils.getAdbTcpPort())
                    if (EnvironmentUtils.getAdbTcpPort() <= 0) applyChange()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun maybePromptRestart (setting: String, newValue: Any? = null, applyChange: () -> Unit) {
        val context = requireContext()
        if (!ShizukuStateMachine.isRunning() || !needsRestart(setting, newValue)) {
            applyChange()
            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))
        } else {
            val message = buildString {
                append(context.getString(R.string.settings_restart_dialog_message))
                if (setting == KEY_TCP_MODE)
                    append(context.getString(R.string.settings_restart_dialog_message_wifi_required))
            }

            MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_restart_dialog_title)
            .setMessage(HtmlCompat.fromHtml(message))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyChange()
                ShizukuReceiverStarter.start(context, true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
    }

    private fun setupLocalePreference(languagePreference: ListPreference) {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES

        languagePreference.entries = displayLocaleTags
        languagePreference.entryValues = localeTags

        val currentLocaleTag = languagePreference.value
        val currentLocaleIndex = localeTags.indexOf(currentLocaleTag)
        val currentLocale = ShizukuSettings.getLocale()
        val localizedLocales = mutableListOf<CharSequence>()

        for ((index, displayLocale) in displayLocaleTags.withIndex()) {
            if (index == 0) {
                localizedLocales.add(getString(R.string.follow_system))
                continue
            }

            val locale = Locale.forLanguageTag(displayLocale.toString())
            val localeName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(locale)
            else
                locale.getDisplayName(locale)

            val localizedLocaleName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(currentLocale)
            else
                locale.getDisplayName(currentLocale)

            localizedLocales.add(
                if (index != currentLocaleIndex) {
                    "$localeName<br><small>$localizedLocaleName<small>".toHtml()
                } else {
                    localizedLocaleName
                }
            )
        }

        languagePreference.entries = localizedLocales.toTypedArray()

        languagePreference.summary = when {
            TextUtils.isEmpty(currentLocaleTag) || "SYSTEM" == currentLocaleTag -> {
                getString(R.string.follow_system)
            }
            currentLocaleIndex != -1 -> {
                val localizedLocale = localizedLocales[currentLocaleIndex]
                val newLineIndex = localizedLocale.indexOf('\n')
                if (newLineIndex == -1) {
                    localizedLocale.toString()
                } else {
                    localizedLocale.subSequence(0, newLineIndex).toString()
                }
            }
            else -> {
                ""
            }
        }
    }
}
