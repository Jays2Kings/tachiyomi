package eu.kanade.tachiyomi.ui.setting

import androidx.biometric.BiometricManager
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.data.updater.UpdaterJob
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.widget.preference.IntListMatPreference
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsGeneralController : SettingsController() {

    private val isUpdaterEnabled = BuildConfig.INCLUDE_UPDATER

    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = R.string.general

        listPreference(activity) {
            key = Keys.lang
            titleRes = R.string.language
            entryValues = listOf(
                "", "ar", "bg", "bn", "ca", "cs", "de", "el", "en-US", "en-GB",
                "es", "fr", "hi", "hu", "in", "it", "ja", "ko", "lv", "ms", "nb-rNO", "nl", "pl", "pt",
                "pt-BR", "ro", "ru", "sc", "sr", "sv", "th", "tl", "tr", "uk", "vi", "zh-rCN"
            )
            entries = entryValues.map { value ->
                val locale = LocaleHelper.getLocaleFromString(value.toString())
                locale?.getDisplayName(locale)?.capitalize()
                    ?: context.getString(R.string.system_default)
            }
            defaultValue = ""
            summary = "%s"

            onChange { newValue ->
                val activity = activity ?: return@onChange false
                val app = activity.application
                LocaleHelper.changeLocale(newValue.toString())
                LocaleHelper.updateConfiguration(app, app.resources.configuration)
                activity.recreate()
                true
            }
        }

        listPreference(activity) {
            key = Keys.dateFormat
            titleRes = R.string.date_format
            entryValues = listOf("", "MM/dd/yy", "dd/MM/yy", "yyyy-MM-dd")
            entries = entryValues.map { value ->
                if (value == "") {
                    context.getString(R.string.system_default)
                } else {
                    value
                }
            }
            defaultValue = ""
            summary = "%s"
        }

        switchPreference {
            key = Keys.confirmExit
            titleRes = R.string.pref_confirm_exit
            defaultValue = false
        }

        switchPreference {
            key = Keys.automaticUpdates
            titleRes = R.string.check_for_updates
            summaryRes = R.string.auto_check_for_app_versions
            defaultValue = true

            if (isUpdaterEnabled) {
                onChange { newValue ->
                    val checked = newValue as Boolean
                    if (checked) {
                        UpdaterJob.setupTask()
                    } else {
                        UpdaterJob.cancelTask()
                    }
                    true
                }
            } else {
                isVisible = false
            }
        }

        preferenceCategory {
            titleRes = R.string.security

            val biometricManager = BiometricManager.from(context)
            if (biometricManager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS) {
                var preference: IntListMatPreference? = null
                switchPreference {
                    key = Keys.useBiometrics
                    titleRes = R.string.lock_with_biometrics
                    defaultValue = false

                    onChange {
                        preference?.isVisible = it as Boolean
                        true
                    }
                }
                preference = intListPreference(activity) {
                    key = Keys.lockAfter
                    titleRes = R.string.lock_when_idle
                    isVisible = preferences.useBiometrics().getOrDefault()
                    val values = listOf(0, 2, 5, 10, 20, 30, 60, 90, 120, -1)
                    entries = values.mapNotNull {
                        when (it) {
                            0 -> context.getString(R.string.always)
                            -1 -> context.getString(R.string.never)
                            else -> resources?.getQuantityString(
                                R.plurals.after_minutes,
                                it.toInt(),
                                it
                            )
                        }
                    }
                    entryValues = values
                    defaultValue = 0
                }
            }

            switchPreference {
                key = Keys.secureScreen
                titleRes = R.string.secure_screen
                summaryRes = R.string.hide_tachi_from_recents
                defaultValue = false

                onChange {
                    it as Boolean
                    SecureActivityDelegate.setSecure(activity, it)
                    true
                }
            }
        }

        preferenceCategory {
            titleRes = R.string.display

            intListPreference(activity) {
                key = Keys.theme
                titleRes = R.string.app_theme
                entriesRes = arrayOf(
                    R.string.white_theme,
                    R.string.light_blue,
                    R.string.dark,
                    R.string.amoled_black,
                    R.string.dark_blue,
                    R.string.system_default,
                    R.string
                        .system_default_amoled,
                    R.string.system_default_all_blue
                )
                entryValues = listOf(1, 8, 2, 3, 4, 5, 6, 7)
                defaultValue = 5

                onChange {
                    activity?.recreate()
                    true
                }
            }

            switchPreference {
                key = Keys.hideBottomNavOnScroll
                titleRes = R.string.hide_bottom_nav
                summaryRes = R.string.hides_on_scroll
                defaultValue = true
            }
        }
    }
}
