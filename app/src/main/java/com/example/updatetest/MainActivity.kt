package com.example.updatetest

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.ActionBarDrawerToggle
import com.google.android.material.navigation.NavigationView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal test harness for the "update via cloud-hosted link" flow.
 * Supports both Google Drive and OneDrive share links (auto-detected and
 * converted to direct-download URLs).
 *
 * 1. Paste a manifest JSON URL (see README for the expected format).
 * 2. Tap "Check for Update" -> fetches JSON, compares versionCode.
 * 3. If newer, offers to download the APK (DownloadManager) and
 *    then triggers the system installer via FileProvider.
 */
class MainActivity : AppCompatActivity() {

    // Set this once to your raw GitHub manifest.json URL, e.g.:
    // "https://raw.githubusercontent.com/USERNAME/REPO/main/manifest.json"
    // The drawer's "Check for Update" item uses this if the text field is empty.
    private val defaultManifestUrl = ""

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var etManifestUrl: EditText
    private lateinit var btnCheck: Button

    private var downloadId: Long = -1L
    private var pendingApkUri: Uri? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                log("Download complete. Preparing installer...")
                promptInstall()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        setSupportActionBar(toolbar)

        // Shows the 3-strip hamburger icon and wires it to open/close the sidebar
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            android.R.string.ok, android.R.string.ok
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // Already home, just close the drawer
                }
                R.id.nav_check_update -> {
                    val url = etManifestUrl.text.toString().trim()
                        .ifEmpty { defaultManifestUrl }
                    if (url.isEmpty()) {
                        log("No manifest URL set. Paste one in the field first.")
                    } else {
                        etManifestUrl.setText(url)
                        checkForUpdate(url)
                    }
                }
                R.id.nav_about -> {
                    AlertDialog.Builder(this)
                        .setTitle("About")
                        .setMessage("Update Test App\nA minimal harness for testing the OneDrive/Drive + GitHub update flow.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            drawerLayout.closeDrawers()
            true
        }

        tvCurrentVersion = findViewById(R.id.tvCurrentVersion)
        tvLog = findViewById(R.id.tvLog)
        scrollView = tvLog.parent as ScrollView
        etManifestUrl = findViewById(R.id.etManifestUrl)
        btnCheck = findViewById(R.id.btnCheck)
        if (defaultManifestUrl.isNotEmpty() && etManifestUrl.text.isEmpty()) {
            etManifestUrl.setText(defaultManifestUrl)
        }

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
        tvCurrentVersion.text = "Current version: ${pInfo.versionName} (code $versionCode)"

        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        )

        btnCheck.setOnClickListener {
            val url = etManifestUrl.text.toString().trim()
            if (url.isEmpty()) {
                log("Enter a manifest JSON URL first.")
                return@setOnClickListener
            }
            checkForUpdate(url)
        }
    }

    override fun onBackPressed() {
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            drawerLayout.closeDrawers()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(downloadReceiver) }
    }

    private fun log(msg: String) {
        runOnUiThread {
            tvLog.append("\n$msg")
            scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    /**
     * Converts a normal share link (Google Drive OR OneDrive) into a
     * direct-download link. Anything else is passed through unchanged.
     */
    private fun toDirectDownloadLink(url: String): String {
        return when {
            url.contains("drive.google.com") -> toDirectDriveLink(url)
            url.contains("1drv.ms") || url.contains("onedrive.live.com") ||
                url.contains(".sharepoint.com") -> toDirectOneDriveLink(url)
            else -> url
        }
    }

    private fun toDirectDriveLink(url: String): String {
        val regex = Regex("""drive\.google\.com/file/d/([^/]+)""")
        val match = regex.find(url)
        return if (match != null) {
            val id = match.groupValues[1]
            "https://drive.google.com/uc?export=download&id=$id"
        } else url
    }

    /**
     * Converts an anonymous OneDrive share link into a direct-download URL.
     * 1drv.ms short links -> append ?download=1
     * onedrive.live.com   -> swap to /download endpoint
     * sharepoint.com      -> Graph API shares token
     */
    private fun toDirectOneDriveLink(url: String): String {
        return when {
            url.contains("1drv.ms") -> {
                val separator = if (url.contains("?")) "&" else "?"
                "${url.trim()}${separator}download=1"
            }
            url.contains("onedrive.live.com") -> {
                url.trim()
                    .replace("redir?", "download?")
                    .replace("action=locate", "")
                    .let { if (!it.contains("download=1")) "${it}&download=1" else it }
            }
            url.contains("sharepoint.com") -> {
                val base64 = Base64.encodeToString(
                    url.trim().toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
                val token = "u!" + base64.trimEnd('=').replace('/', '_').replace('+', '-')
                "https://api.onedrive.com/v1.0/shares/$token/root/content"
            }
            else -> url
        }
    }

    private fun checkForUpdate(manifestUrl: String) {
        log("Fetching manifest: $manifestUrl")
        Thread {
            try {
                val direct = toDirectDownloadLink(manifestUrl)
                var conn = URL(direct).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true

                // OneDrive's /shares/.../root/content endpoint responds with a
                // 302 to the real file. HttpURLConnection normally follows
                // redirects automatically, but not always across host names,
                // so follow manually as a fallback (max 5 hops).
                var redirects = 0
                while (conn.responseCode in intArrayOf(301, 302, 303, 307, 308) && redirects < 5) {
                    val nextUrl = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = URL(nextUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = true
                    redirects++
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val body = reader.readText()
                reader.close()

                log("Response (first 200 chars): ${body.take(200)}")

                if (body.trimStart().startsWith("<")) {
                    log("ERROR: Got an HTML page instead of JSON. Check your URL is a raw/direct link.")
                    return@Thread
                }

                val json = JSONObject(body)
                val remoteVersionCode = json.getInt("versionCode")
                val remoteVersionName = json.optString("versionName", "")
                val apkUrl = json.getString("apkUrl")
                val notes = json.optString("releaseNotes", "")

                val pInfo = packageManager.getPackageInfo(packageName, 0)
                val localVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()

                log("Remote version: $remoteVersionName (code $remoteVersionCode)")

                if (remoteVersionCode > localVersionCode) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Update available")
                            .setMessage("Version $remoteVersionName is available.\n\n$notes")
                            .setPositiveButton("Download") { _, _ ->
                                startDownload(toDirectDownloadLink(apkUrl))
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    }
                } else {
                    log("You're already on the latest version.")
                }
            } catch (e: Exception) {
                log("Error checking update: ${e.message}")
            }
        }.start()
    }

    private fun startDownload(apkUrl: String) {
        log("Starting download: $apkUrl")
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("App update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "update.apk")
            .setAllowedOverMetered(true)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)

        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        pendingApkUri = FileProvider.getUriForFile(this, "$packageName.provider", apkFile)
    }

    private fun promptInstall() {
        // Android 8+ requires the user to explicitly allow this app to install unknown apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            log("Need 'Install unknown apps' permission. Opening settings...")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }

        val uri = pendingApkUri ?: return
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(installIntent)
    }
}
