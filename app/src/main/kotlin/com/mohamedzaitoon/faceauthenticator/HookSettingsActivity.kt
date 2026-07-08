package com.mohamedzaitoon.faceauthenticator

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class HookSettingsActivity : Activity() {

    private lateinit var statusBadge: TextView
    private lateinit var delayInput: EditText
    private lateinit var delayedFaceCheckBox: CheckBox
    private lateinit var showMessagesCheckBox: CheckBox
    private lateinit var instantConfirmationCheckBox: CheckBox
    private lateinit var keystoreCheckBox: CheckBox
    private lateinit var rebootButton: Button

    private val suCandidates = listOf(
        "/product/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "su"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#F2F4F8"))
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 70, 50, 70)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 40f
                setStroke(2, Color.parseColor("#E0E0E0"))
            }
            elevation = 10f
        }

        cardLayout.addView(TextView(this).apply {
            text = "Face Authenticator"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
        })

        statusBadge = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(40, 15, 40, 15)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 30, 0, 30) }
        }
        setStatusBadge("Checking...", "#455A64", "#ECEFF1")
        cardLayout.addView(statusBadge)

        val versionInfo = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        cardLayout.addView(TextView(this).apply {
            text = "Version $versionInfo"
            textSize = 16f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 34)
        })

        addHookSettingsSection(cardLayout)

        rebootButton = Button(this).apply {
            text = "Reboot Device"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = getRoundedButtonDrawable("#455A64")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                130
            ).apply { setMargins(20, 16, 20, 20) }
            setOnClickListener { rebootDeviceWithRoot() }
        }
        cardLayout.addView(rebootButton)

        cardLayout.addView(TextView(this).apply {
            text = "Settings are stored locally on the device."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        rootLayout.addView(cardLayout)
        scrollView.addView(rootLayout)
        setContentView(scrollView)

        refreshModuleStatusAsync()
    }

    private fun addHookSettingsSection(cardLayout: LinearLayout) {
        cardLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 2).apply {
                setMargins(0, 10, 0, 30)
            }
            setBackgroundColor(Color.LTGRAY)
        })

        cardLayout.addView(TextView(this).apply {
            text = "Hook Settings"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        })

        cardLayout.addView(TextView(this).apply {
            text = "Face fallback delay (seconds)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#263238"))
            gravity = Gravity.START
        })

        delayInput = EditText(this).apply {
            setText(DEFAULT_FACE_DELAY_SECONDS)
            hint = "0.001 - 10"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#111111"))
            setHintTextColor(Color.parseColor("#78909C"))
            setPadding(32, 0, 32, 0)
            setSelectAllOnFocus(true)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 22f
                setStroke(3, Color.parseColor("#00796B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                120
            ).apply { setMargins(20, 12, 20, 8) }
        }
        cardLayout.addView(delayInput)

        cardLayout.addView(TextView(this).apply {
            text = "Default 1.5s. Minimum 0.001s, maximum 10s."
            textSize = 12f
            setTextColor(Color.parseColor("#607D8B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        delayedFaceCheckBox = createHookCheckBox("Enable delayed face fallback", true)
        showMessagesCheckBox = createHookCheckBox("Show delay messages", false)
        instantConfirmationCheckBox = createHookCheckBox("Instant face confirmation", true)
        keystoreCheckBox = createHookCheckBox("Keystore/Crypto apps use fingerprint only", true)
        cardLayout.addView(delayedFaceCheckBox)
        cardLayout.addView(showMessagesCheckBox)
        cardLayout.addView(instantConfirmationCheckBox)
        cardLayout.addView(keystoreCheckBox)

        cardLayout.addView(Button(this).apply {
            text = "Save Hook Settings"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = getRoundedButtonDrawable("#00796B")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                120
            ).apply { setMargins(20, 20, 20, 20) }
            setOnClickListener { saveHookSettingsAsync() }
        })

        loadHookSettingsIntoUi()
    }

    private fun createHookCheckBox(label: String, checked: Boolean): CheckBox {
        return CheckBox(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#263238"))
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(10, 0, 10, 0) }
        }
    }

    private fun loadHookSettingsIntoUi() {
        val settings = readHookSettings()
        delayInput.setText(formatDelaySeconds(settings.delayMs))
        delayedFaceCheckBox.isChecked = settings.delayedFaceEnabled
        showMessagesCheckBox.isChecked = settings.showStatusMessages
        instantConfirmationCheckBox.isChecked = settings.instantFaceConfirmation
        keystoreCheckBox.isChecked = settings.keystoreFingerprintOnly
    }

    private fun readHookSettings(): UiHookSettings {
        val file = File(HOOK_SETTINGS_FILE)
        if (!file.exists()) {
            return UiHookSettings()
        }

        return try {
            val values = HashMap<String, String>()
            file.readLines().forEach { line ->
                val clean = line.substringBefore('#').trim()
                val separator = clean.indexOf('=')
                if (separator > 0) {
                    values[clean.substring(0, separator).trim()] =
                        clean.substring(separator + 1).trim()
                }
            }

            UiHookSettings(
                delayMs = parseDelayMs(values["face_delay_ms"]),
                delayedFaceEnabled = parseBooleanSetting(
                    values["delayed_face_enabled"],
                    true
                ),
                showStatusMessages = parseBooleanSetting(
                    values["show_status_messages"],
                    false
                ),
                instantFaceConfirmation = parseBooleanSetting(
                    values["instant_face_confirmation"],
                    true
                ),
                keystoreFingerprintOnly = parseBooleanSetting(
                    values["keystore_fingerprint_only"],
                    true
                )
            )
        } catch (_: Exception) {
            UiHookSettings()
        }
    }

    private fun saveHookSettingsAsync() {
        val settings = UiHookSettings(
            delayMs = parseDelaySecondsInput(delayInput.text?.toString().orEmpty()),
            delayedFaceEnabled = delayedFaceCheckBox.isChecked,
            showStatusMessages = showMessagesCheckBox.isChecked,
            instantFaceConfirmation = instantConfirmationCheckBox.isChecked,
            keystoreFingerprintOnly = keystoreCheckBox.isChecked
        )
        delayInput.setText(formatDelaySeconds(settings.delayMs))

        val content = buildString {
            append("delayed_face_enabled=${settings.delayedFaceEnabled}\n")
            append("face_delay_ms=${settings.delayMs}\n")
            append("show_status_messages=${settings.showStatusMessages}\n")
            append("instant_face_confirmation=${settings.instantFaceConfirmation}\n")
            append("keystore_fingerprint_only=${settings.keystoreFingerprintOnly}\n")
        }

        Thread {
            val success = runWithRoot(
                "printf %s ${shellQuote(content)} > ${shellQuote(HOOK_SETTINGS_FILE)} && " +
                    "chmod 0644 ${shellQuote(HOOK_SETTINGS_FILE)}"
            )
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (success) "Hook settings saved. Reboot or restart System Framework." else
                        "Root permission is required to save hook settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun getRoundedButtonDrawable(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = 30f
        }
    }

    private fun refreshModuleStatusAsync() {
        if (isHookMarkerActive()) {
            setStatusBadge("Active", "#2E7D32", "#E8F5E9")
            return
        }

        Thread {
            val enabledInLsposed = isModuleEnabledInLsposedByRoot()
            runOnUiThread {
                if (isHookMarkerActive()) {
                    setStatusBadge("Active", "#2E7D32", "#E8F5E9")
                } else if (enabledInLsposed) {
                    setStatusBadge("Enabled", "#1565C0", "#E3F2FD")
                } else {
                    setStatusBadge("Unknown", "#6A4F00", "#FFF8E1")
                }
            }
        }.start()
    }

    private fun setStatusBadge(text: String, textColor: String, backgroundColor: String) {
        statusBadge.text = text
        statusBadge.setTextColor(Color.parseColor(textColor))
        statusBadge.background = GradientDrawable().apply {
            setColor(Color.parseColor(backgroundColor))
            cornerRadius = 50f
        }
    }

    private fun rebootDeviceWithRoot() {
        rebootButton.isEnabled = false
        rebootButton.text = "Requesting Root..."
        Thread {
            val success = runWithRoot("reboot")
            if (!success) {
                runOnUiThread {
                    rebootButton.isEnabled = true
                    rebootButton.text = "Reboot Device"
                    Toast.makeText(
                        this,
                        "Root permission is required to reboot.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun runWithRoot(command: String): Boolean {
        for (suPath in suCandidates) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", command))
                if (process.waitFor() == 0) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun isHookMarkerActive(): Boolean {
        return try {
            val currentBoot = Settings.Global.getInt(
                contentResolver,
                Settings.Global.BOOT_COUNT,
                -1
            )
            val activeBoot = Settings.Global.getInt(
                contentResolver,
                ACTIVE_BOOT_KEY,
                -2
            )
            currentBoot >= 0 && activeBoot == currentBoot
        } catch (_: Exception) {
            false
        }
    }

    private fun isModuleEnabledInLsposedByRoot(): Boolean {
        val escapedPackage = packageName.replace("'", "'\"'\"'")
        val command = "grep -R -a -q '$escapedPackage' " +
            "/data/adb/lspd/config /data/adb/lspd/modules " +
            "/data/adb/modules/zygisk_lsposed /data/adb/modules/riru_lsposed 2>/dev/null"
        return runWithRoot(command)
    }

    private fun parseDelaySecondsInput(rawValue: String): Long {
        val seconds = rawValue.trim().toDoubleOrNull() ?: DEFAULT_FACE_DELAY_SECONDS.toDouble()
        return Math.round(seconds.coerceIn(MIN_FACE_DELAY_SECONDS, MAX_FACE_DELAY_SECONDS) * 1000.0)
            .coerceIn(MIN_FACE_DELAY_MS, MAX_FACE_DELAY_MS)
    }

    private fun parseDelayMs(rawValue: String?): Long {
        val value = rawValue?.toLongOrNull() ?: DEFAULT_FACE_DELAY_MS
        return value.coerceIn(MIN_FACE_DELAY_MS, MAX_FACE_DELAY_MS)
    }

    private fun parseBooleanSetting(rawValue: String?, fallback: Boolean): Boolean {
        return when (rawValue?.trim()?.lowercase()) {
            "1", "true", "yes", "on", "enabled" -> true
            "0", "false", "no", "off", "disabled" -> false
            else -> fallback
        }
    }

    private fun formatDelaySeconds(delayMs: Long): String {
        val seconds = delayMs / 1000.0
        return if (seconds >= 1.0) {
            String.format(java.util.Locale.US, "%.3f", seconds).trimEnd('0').trimEnd('.')
        } else {
            String.format(java.util.Locale.US, "%.3f", seconds)
        }
    }

    private data class UiHookSettings(
        val delayMs: Long = DEFAULT_FACE_DELAY_MS,
        val delayedFaceEnabled: Boolean = true,
        val showStatusMessages: Boolean = false,
        val instantFaceConfirmation: Boolean = true,
        val keystoreFingerprintOnly: Boolean = true
    )

    private companion object {
        private const val ACTIVE_BOOT_KEY = "faceauthenticator_active_boot"
        private const val HOOK_SETTINGS_FILE = "/data/local/tmp/faceauth_settings.properties"
        private const val DEFAULT_FACE_DELAY_MS = 1500L
        private const val MIN_FACE_DELAY_MS = 1L
        private const val MAX_FACE_DELAY_MS = 10000L
        private const val DEFAULT_FACE_DELAY_SECONDS = "1.5"
        private const val MIN_FACE_DELAY_SECONDS = 0.001
        private const val MAX_FACE_DELAY_SECONDS = 10.0
    }
}
