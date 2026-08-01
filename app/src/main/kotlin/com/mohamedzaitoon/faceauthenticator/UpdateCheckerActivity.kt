package com.mohamedzaitoon.faceauthenticator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

class UpdateCheckerActivity : Activity() {

    private var currentGithubUrl = UpdateChecker.DEFAULT_GITHUB_URL

    private var downloadId: Long = -1
    private var downloadFileName: String = ""
    private var downloadedFile: File? = null

    private var progressDialog: AlertDialog? = null
    private var dialogProgressBar: ProgressBar? = null
    private var dialogPercentText: TextView? = null
    private var isDownloading = false

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var updateButton: Button
    private lateinit var rebootButton: Button
    private lateinit var githubButton: TextView
    private lateinit var statusBadge: TextView
    private lateinit var delayInput: EditText
    private lateinit var delayedFaceCheckBox: CheckBox
    private lateinit var showMessagesCheckBox: CheckBox
    private lateinit var instantConfirmationCheckBox: CheckBox
    private lateinit var keystoreCheckBox: CheckBox

    private val suCandidates = listOf(
        "/product/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "su"
    )

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureLauncherIconHidden()

        swipeRefreshLayout = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#2196F3"))
            setProgressBackgroundColorSchemeColor(Color.WHITE)
        }

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
            setPadding(50, 80, 50, 80)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 40f
                setStroke(2, Color.parseColor("#E0E0E0"))
            }
            elevation = 10f
        }

        val titleView = TextView(this).apply {
            text = "Face Authenticator"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
        }
        cardLayout.addView(titleView)

        statusBadge = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(40, 15, 40, 15)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 30, 0, 50) }
        }
        setStatusBadge("Checking...", "#455A64", "#ECEFF1")
        cardLayout.addView(statusBadge)

        val versionInfo = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val versionView = TextView(this).apply {
            text = "Version $versionInfo"
            textSize = 16f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        cardLayout.addView(versionView)

        cardLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 2).apply {
                setMargins(0, 40, 0, 40)
            }
            setBackgroundColor(Color.LTGRAY)
        })

        updateButton = Button(this).apply {
            text = "Check for Updates"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = getRoundedButtonDrawable("#BDBDBD")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                140
            ).apply { setMargins(20, 10, 20, 30) }
            setOnClickListener { checkForUpdates() }
        }
        cardLayout.addView(updateButton)

        rebootButton = Button(this).apply {
            text = "Reboot Device"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = getRoundedButtonDrawable("#455A64")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                140
            ).apply { setMargins(20, 0, 20, 20) }
            setOnClickListener { rebootDeviceWithRoot() }
        }
        cardLayout.addView(rebootButton)

        addHookSettingsSection(cardLayout)

        val linksLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 0)
        }

        fun createGithubLink(initialUrl: String): TextView {
            val textView = TextView(this).apply {
                text = "GitHub"
                textSize = 14f
                setTextColor(Color.parseColor("#2196F3"))
                setPadding(30, 20, 30, 20)
                gravity = Gravity.CENTER
                compoundDrawablePadding = 10
                val githubIcon = ContextCompat
                    .getDrawable(this@UpdateCheckerActivity, R.drawable.ic_github)
                    ?.mutate()
                    ?.apply {
                        setTint(Color.parseColor("#2196F3"))
                        setBounds(0, 0, 36, 36)
                    }
                setCompoundDrawables(githubIcon, null, null, null)
                setOnClickListener { openUrl(initialUrl) }
            }
            linksLayout.addView(textView)
            return textView
        }

        githubButton = createGithubLink(currentGithubUrl)
        cardLayout.addView(linksLayout)

        val devInfo = TextView(this).apply {
            text = "© Mohamed Zaitoon"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 0)
        }
        cardLayout.addView(devInfo)

        rootLayout.addView(cardLayout)
        scrollView.addView(rootLayout)
        swipeRefreshLayout.addView(scrollView)
        setContentView(swipeRefreshLayout)

        swipeRefreshLayout.setOnRefreshListener { checkForUpdates() }
        refreshModuleStatusAsync()
        checkForUpdates()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                onDownloadComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                onDownloadComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun addHookSettingsSection(cardLayout: LinearLayout) {
        cardLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 2).apply {
                setMargins(0, 20, 0, 30)
            }
            setBackgroundColor(Color.LTGRAY)
        })

        val settingsTitle = TextView(this).apply {
            text = "Hook Settings"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        cardLayout.addView(settingsTitle)

        val delayLabel = TextView(this).apply {
            text = "Face fallback delay (seconds)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#263238"))
            gravity = Gravity.START
        }
        cardLayout.addView(delayLabel)

        delayInput = EditText(this).apply {
            setText(DEFAULT_FACE_DELAY_SECONDS)
            hint = "0.001 - 10"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
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

        val delayHint = TextView(this).apply {
            text = "Default 5s. Minimum 0.001s, maximum 10s."
            textSize = 12f
            setTextColor(Color.parseColor("#607D8B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        cardLayout.addView(delayHint)

        delayedFaceCheckBox = createHookCheckBox("Enable delayed face fallback", true)
        showMessagesCheckBox = createHookCheckBox("Show delay messages", false)
        instantConfirmationCheckBox = createHookCheckBox("Instant face confirmation", true)
        keystoreCheckBox = createHookCheckBox("Keystore/Crypto apps use fingerprint only", false)
        cardLayout.addView(delayedFaceCheckBox)
        cardLayout.addView(showMessagesCheckBox)
        cardLayout.addView(instantConfirmationCheckBox)
        cardLayout.addView(keystoreCheckBox)

        val saveSettingsButton = Button(this).apply {
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
        }
        cardLayout.addView(saveSettingsButton)
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

    private fun ensureLauncherIconHidden() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "$packageName.LauncherActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {
        }
    }

    private fun checkForUpdates() {
        if (!swipeRefreshLayout.isRefreshing) {
            updateButton.text = "Checking..."
            updateButton.isEnabled = false
            updateButton.background = getRoundedButtonDrawable("#BDBDBD")
        }

        UpdateChecker.checkForUpdate(this, object : UpdateChecker.UpdateListener {
            override fun onConfigFetched(result: UpdateChecker.ConfigResult) {
                swipeRefreshLayout.isRefreshing = false

                currentGithubUrl = result.githubUrl.ifBlank { UpdateChecker.DEFAULT_GITHUB_URL }
                githubButton.setOnClickListener { openUrl(currentGithubUrl) }

                if (result.updateAvailable) {
                    updateButton.text = "Download Update (${result.latestVersionName})"
                    updateButton.background = getRoundedButtonDrawable("#2196F3")
                    updateButton.isEnabled = true
                    updateButton.setOnClickListener {
                        startInternalDownload(result.downloadUrl, result.latestVersionName)
                    }
                    Toast.makeText(
                        this@UpdateCheckerActivity,
                        "Update Available: ${result.latestVersionName}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    updateButton.text = "Latest Version Installed"
                    updateButton.background = getRoundedButtonDrawable("#4CAF50")
                    updateButton.isEnabled = false
                }
            }

            override fun onError(error: String) {
                swipeRefreshLayout.isRefreshing = false
                updateButton.text = "Check Failed (Tap to Retry)"
                updateButton.background = getRoundedButtonDrawable("#F44336")
                updateButton.isEnabled = true
                updateButton.setOnClickListener { checkForUpdates() }
                Toast.makeText(this@UpdateCheckerActivity, "Error: $error", Toast.LENGTH_LONG)
                    .show()
            }
        })
    }

    private fun startInternalDownload(url: String, version: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Download URL is missing", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.contains(".apk", ignoreCase = true)) {
            openUrl(url)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                })
                Toast.makeText(
                    this,
                    "Please allow install permission for fallback installation",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            downloadFileName = "FaceAuthenticator_$version.apk"
            val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && !downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            downloadedFile = downloadsDir?.let { File(it, downloadFileName) }
            downloadedFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Downloading Face Authenticator $version")
                .setDescription("Downloading update...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    this,
                    Environment.DIRECTORY_DOWNLOADS,
                    downloadFileName
                )
                .setMimeType(APK_MIME_TYPE)

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = manager.enqueue(request)

            showProgressDialog()
            startDownloadWatcher(manager)
        } catch (e: Exception) {
            Toast.makeText(this, "Download Error: ${e.message}", Toast.LENGTH_SHORT).show()
            openUrl(url)
        }
    }

    private fun showProgressDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "Downloading..."
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        dialogView.addView(title)

        dialogProgressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = false
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialogView.addView(dialogProgressBar)

        dialogPercentText = TextView(this).apply {
            text = "0%"
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }
        dialogView.addView(dialogPercentText)

        progressDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        progressDialog?.show()
    }

    private fun startDownloadWatcher(manager: DownloadManager) {
        isDownloading = true
        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                if (!isDownloading) {
                    return
                }

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor = manager.query(query) ?: return
                try {
                    if (cursor.moveToFirst()) {
                        val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusCol > -1) {
                            val status = cursor.getInt(statusCol)
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                isDownloading = false
                                progressDialog?.dismiss()
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                isDownloading = false
                                progressDialog?.dismiss()
                                Toast.makeText(
                                    this@UpdateCheckerActivity,
                                    "Download Failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                updateDownloadProgress(cursor)
                                handler.postDelayed(this, DOWNLOAD_WATCH_INTERVAL_MS)
                            }
                        }
                    } else {
                        handler.postDelayed(this, DOWNLOAD_WATCH_INTERVAL_MS)
                    }
                } finally {
                    cursor.close()
                }
            }
        }
        handler.post(runnable)
    }

    private fun updateDownloadProgress(cursor: Cursor) {
        val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        if (bytesCol <= -1 || totalCol <= -1) {
            return
        }

        val current = cursor.getLong(bytesCol)
        val total = cursor.getLong(totalCol)
        if (total > 0L) {
            val progress = ((current * 100L) / total).toInt()
            dialogProgressBar?.progress = progress
            dialogPercentText?.text = "$progress%"
        }
    }

    private fun getRoundedButtonDrawable(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = 30f
        }
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId == id) {
                isDownloading = false
                progressDialog?.dismiss()
                handleInstallation(id)
            }
        }
    }

    private fun handleInstallation(downloadId: Long) {
        val file = downloadedFile
        if (file != null && file.exists()) {
            Toast.makeText(this, "Installing via root...", Toast.LENGTH_SHORT).show()
            val success = installWithRoot(file.absolutePath)
            if (success) {
                return
            }
        }
        installStandard(downloadId)
    }

    private fun installWithRoot(path: String): Boolean {
        return runWithRoot("pm install -r \"$path\"")
    }

    private fun installStandard(downloadId: Long) {
        try {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(downloadId)
                ?: downloadedFile?.let {
                    FileProvider.getUriForFile(this, "$packageName.provider", it)
                }
            if (uri != null) {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(installIntent)
            } else {
                Toast.makeText(this, "Install Failed: file missing", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Install Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isDownloading = false
        progressDialog?.dismiss()
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (_: Exception) {
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Link is not available yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestRootAccessAsync() {
        Thread {
            if (!requestRootAccess()) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Grant root permission from Magisk for full features.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
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

    private fun requestRootAccess(): Boolean {
        return runWithRoot("id")
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

    private data class UiHookSettings(
        val delayMs: Long = DEFAULT_FACE_DELAY_MS,
        val delayedFaceEnabled: Boolean = true,
        val showStatusMessages: Boolean = false,
        val instantFaceConfirmation: Boolean = true,
        val keystoreFingerprintOnly: Boolean = false
    )

    private companion object {
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val DOWNLOAD_WATCH_INTERVAL_MS = 250L
        private const val ACTIVE_BOOT_KEY = "faceauthenticator_active_boot"
        private const val HOOK_SETTINGS_FILE = "/data/local/tmp/faceauth_settings.properties"
        private const val DEFAULT_FACE_DELAY_MS = 5000L
        private const val MIN_FACE_DELAY_MS = 1L
        private const val MAX_FACE_DELAY_MS = 10000L
        private const val DEFAULT_FACE_DELAY_SECONDS = "5"
        private const val MIN_FACE_DELAY_SECONDS = 0.001
        private const val MAX_FACE_DELAY_SECONDS = 10.0
    }
}
