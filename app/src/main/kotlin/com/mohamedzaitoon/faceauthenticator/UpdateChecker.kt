package com.mohamedzaitoon.faceauthenticator

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TAG = "FaceAuthUpdater"
    const val DEFAULT_GITHUB_URL = "https://github.com/mohamed-zaitoon/FaceAuthenticator"
    const val DEFAULT_RELEASES_URL = "$DEFAULT_GITHUB_URL/releases/latest"

    private const val GITHUB_USER = "mohamed-zaitoon"
    private const val GITHUB_REPO = "FaceAuthenticator"
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases?per_page=20"
    private const val KEY_LATEST_VERSION_CODE = "latest_version_code"
    private const val KEY_LATEST_VERSION_NAME = "latest_version_name"
    private const val KEY_UPDATE_URL = "update_url"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_GITHUB_URL = "github_url"
    private const val KEY_WEBSITE_URL = "website_url"

    data class ConfigResult(
        val githubUrl: String,
        val websiteUrl: String,
        val updateAvailable: Boolean = false,
        val latestVersionName: String = "",
        val downloadUrl: String = "",
        val releaseNotes: String = ""
    )

    interface UpdateListener {
        fun onConfigFetched(result: ConfigResult)
        fun onError(error: String)
    }

    private data class GitHubRelease(
        val versionName: String,
        val versionCode: Long,
        val downloadUrl: String,
        val htmlUrl: String,
        val notes: String
    )

    fun checkForUpdate(context: Context, listener: UpdateListener) {
        try {
            val remoteConfig = FirebaseRemoteConfig.getInstance()
            remoteConfig.setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(0)
                    .build()
            )
            remoteConfig.setDefaultsAsync(
                mapOf(
                    KEY_LATEST_VERSION_CODE to 0L,
                    KEY_LATEST_VERSION_NAME to "",
                    KEY_UPDATE_URL to "AUTO",
                    KEY_RELEASE_NOTES to "",
                    KEY_GITHUB_URL to DEFAULT_GITHUB_URL,
                    KEY_WEBSITE_URL to DEFAULT_RELEASES_URL
                )
            ).continueWithTask { remoteConfig.fetchAndActivate() }
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w(TAG, "Remote Config fetch failed; using activated values", task.exception)
                    }
                    processRemoteConfig(context, remoteConfig, listener)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Remote Config initialization failed: ${e.message}", e)
            fetchGitHubFallback(context, listener, e.message)
        }
    }

    private fun processRemoteConfig(
        context: Context,
        remoteConfig: FirebaseRemoteConfig,
        listener: UpdateListener
    ) {
        try {
            val latestCode = remoteConfig.getLong(KEY_LATEST_VERSION_CODE)
            val latestName = remoteConfig.getString(KEY_LATEST_VERSION_NAME)
            val updateUrl = remoteConfig.getString(KEY_UPDATE_URL)
            val releaseNotes = remoteConfig.getString(KEY_RELEASE_NOTES)
            val githubUrl = remoteConfig.getString(KEY_GITHUB_URL).ifBlank {
                DEFAULT_GITHUB_URL
            }
            val websiteUrl = remoteConfig.getString(KEY_WEBSITE_URL).ifBlank {
                DEFAULT_RELEASES_URL
            }

            Log.d(TAG, "Remote Config activated: versionCode=$latestCode, versionName=$latestName")
            if (latestCode <= 0L) {
                fetchGitHubFallback(context, listener, null, githubUrl, websiteUrl)
                return
            }

            if (latestCode <= getAppVersionCode(context)) {
                listener.onConfigFetched(
                    ConfigResult(
                        githubUrl = githubUrl,
                        websiteUrl = websiteUrl,
                        latestVersionName = latestName,
                        releaseNotes = releaseNotes
                    )
                )
                return
            }

            if (updateUrl.isBlank() || updateUrl.equals("AUTO", ignoreCase = true)) {
                fetchDynamicUrlFromGitHub { release, _ ->
                    listener.onConfigFetched(
                        ConfigResult(
                            githubUrl = githubUrl,
                            websiteUrl = release?.htmlUrl?.ifBlank { websiteUrl } ?: websiteUrl,
                            updateAvailable = true,
                            latestVersionName = latestName.ifBlank {
                                release?.versionName ?: "Version $latestCode"
                            },
                            downloadUrl = release?.downloadUrl?.ifBlank { websiteUrl } ?: websiteUrl,
                            releaseNotes = releaseNotes.ifBlank { release?.notes.orEmpty() }
                        )
                    )
                }
            } else {
                listener.onConfigFetched(
                    ConfigResult(
                        githubUrl = githubUrl,
                        websiteUrl = websiteUrl,
                        updateAvailable = true,
                        latestVersionName = latestName.ifBlank { "Version $latestCode" },
                        downloadUrl = updateUrl,
                        releaseNotes = releaseNotes
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remote Config processing failed: ${e.message}", e)
            fetchGitHubFallback(context, listener, e.message)
        }
    }

    private fun fetchGitHubFallback(
        context: Context,
        listener: UpdateListener,
        previousError: String?,
        githubUrl: String = DEFAULT_GITHUB_URL,
        websiteUrl: String = DEFAULT_RELEASES_URL
    ) {
        fetchDynamicUrlFromGitHub { release, error ->
            if (release == null) {
                Log.e(TAG, "GitHub fallback failed: $error")
                if (previousError == null) {
                    listener.onError(error ?: "GitHub update check failed")
                } else {
                    listener.onError("$previousError; ${error ?: "GitHub update check failed"}")
                }
                return@fetchDynamicUrlFromGitHub
            }

            listener.onConfigFetched(
                ConfigResult(
                    githubUrl = githubUrl,
                    websiteUrl = release.htmlUrl.ifBlank { websiteUrl },
                    updateAvailable = isNewerRelease(context, release),
                    latestVersionName = release.versionName,
                    downloadUrl = release.downloadUrl.ifBlank { release.htmlUrl },
                    releaseNotes = release.notes
                )
            )
        }
    }

    private fun fetchDynamicUrlFromGitHub(callback: (GitHubRelease?, String?) -> Unit) {
        Thread {
            val result = try {
                readLatestGitHubRelease() to null
            } catch (e: Exception) {
                Log.e(TAG, "GitHub release fetch failed: ${e.message}", e)
                null to e.message
            }

            Handler(Looper.getMainLooper()).post {
                callback(result.first, result.second)
            }
        }.apply { name = "FaceAuthUpdateChecker" }.start()
    }

    private fun readLatestGitHubRelease(): GitHubRelease {
        val connection = (URL(GITHUB_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", "FaceAuthenticator-App")
            setRequestProperty("Accept", "application/vnd.github.v3+json")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("GitHub Error: $responseCode")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(response)
            if (releases.length() == 0) {
                throw IllegalStateException("No GitHub releases found")
            }

            var latestRelease: GitHubRelease? = null
            for (i in 0 until releases.length()) {
                val release = parseGitHubRelease(releases.getJSONObject(i))
                if (latestRelease == null) {
                    latestRelease = release
                }
                if (release.downloadUrl.isNotBlank()) {
                    return release
                }
            }

            latestRelease ?: throw IllegalStateException("No GitHub releases found")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGitHubRelease(json: JSONObject): GitHubRelease {
        val notes = json.optString("body", "")
        val tagName = json.optString("tag_name", "")
        val releaseName = json.optString("name", "")
        val htmlUrl = json.optString("html_url", DEFAULT_RELEASES_URL)

        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
        }

        return GitHubRelease(
            versionName = parseVersionName(notes).ifBlank {
                tagName.ifBlank { releaseName.ifBlank { "GitHub release" } }
            },
            versionCode = parseVersionCode(notes),
            downloadUrl = apkUrl,
            htmlUrl = htmlUrl,
            notes = notes.ifBlank { "GitHub release fetched successfully." }
        )
    }

    private fun isNewerRelease(context: Context, release: GitHubRelease): Boolean {
        if (release.versionCode > 0L) {
            return release.versionCode > getAppVersionCode(context)
        }

        val latest = normalizeVersionName(release.versionName)
        val current = normalizeVersionName(getAppVersionName(context))
        return latest.isNotBlank() && latest != current
    }

    private fun parseVersionCode(notes: String): Long {
        val patterns = arrayOf(
            Regex("""(?i)version\s*code\s*[:=]\s*(\d+)"""),
            Regex("""(?i)versionCode\s*[:=]\s*(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(notes)
            if (match != null) {
                return match.groupValues[1].toLongOrNull() ?: 0L
            }
        }
        return 0L
    }

    private fun parseVersionName(notes: String): String {
        return Regex("""(?i)version\s*name\s*[:=]\s*([^\n\r]+)""")
            .find(notes)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private fun normalizeVersionName(versionName: String): String {
        return versionName.trim()
            .removePrefix("Version ")
            .removePrefix("version ")
            .removePrefix("v")
            .removePrefix("V")
            .trim()
    }

    private fun getAppVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun getAppVersionCode(context: Context): Long {
        return try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
