package com.example.ipspeed

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var importedIPs: List<String> = emptyList()
    private var resultLines: List<String> = emptyList()
    private var sourcePage = false
    private var githubPage = false
    private var geoRowsByIP: Map<String, String> = emptyMap()
    private var countryCodesByIP: Map<String, String> = emptyMap()
    private lateinit var githubTokenInput: EditText
    private lateinit var githubUsernameInput: EditText
    private lateinit var githubRepoInput: EditText
    private lateinit var githubBranchInput: EditText
    private lateinit var githubPathInput: EditText
    private lateinit var githubModeGroup: RadioGroup
    private var githubAutoStatusView: TextView? = null
    private var githubConnectionStatusView: TextView? = null
    private var githubLastUploadTimeView: TextView? = null
    private var githubLastUploadCountView: TextView? = null

    private data class GitHubConfig(
        val token: String,
        val username: String,
        val repository: String,
        val branch: String,
        val path: String,
        val mode: String
    )

    private data class GeoMetadata(
        val rows: Map<String, String>,
        val countries: Map<String, String>
    )

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var resultsView: TextView
    private lateinit var dataCenterSummaryView: TextView
    private lateinit var targetInput: EditText
    private lateinit var targetPresetSpinner: Spinner
    private var updatingTargetPreset = false
    private var lastTargetPresetPosition = -1
    private lateinit var connectInput: EditText
    private lateinit var downloadInput: EditText
    private lateinit var topNInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var urlsInput: EditText
    private lateinit var localCheck: CheckBox
    private lateinit var networkCheck: CheckBox
    private lateinit var sourceStats: TextView

    private val defaults = listOf(
        "https://zip.cm.edu.kg/all.txt",
        "https://countrymerge.pages.dev/all.txt"
    )

    private val targetPresetLabels = arrayOf("自动选择", "Cloudflare", "CM提供", "移动专属", "手动输入")
    private val autoTargetLabel = "自动选择"
    private val defaultTargetUrl = "https://speed.cloudflare.com/__down?bytes=50000000"
    private val cloudflareTargetUrl = "https://speed.cloudflare.com/__down?bytes=99999999"
    private val cmTargetUrl = "https://cf.090227.xyz/__down?bytes=99999999"
    private val mobileTargetUrl = "https://speed.okl.abrdns.com"

    private var savedDataLoaded = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val bounds = android.graphics.Rect()
                focused.getGlobalVisibleRect(bounds)
                val tappedInsideInput = bounds.contains(ev.rawX.toInt(), ev.rawY.toInt())
                if (!tappedInsideInput) {
                    (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                        ?.hideSoftInputFromWindow(focused.windowToken, 0)
                    focused.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMain()
        status.text = "状态：正在加载本地数据…"
        executor.execute {
            val loadedIPs = prefs.getString("imported_ips", "").orEmpty()
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
            val loadedRows = readStringMap(prefs.getString("ip_geo_rows", "{}"))
            val loadedCountries = readStringMap(prefs.getString("ip_country_codes", "{}"))
            runOnUiThread {
                importedIPs = loadedIPs
                geoRowsByIP = loadedRows
                countryCodesByIP = loadedCountries
                savedDataLoaded = true
                if (::sourceStats.isInitialized) sourceStats.text = "已保存唯一 IPv4：${importedIPs.size}"
                status.text = "状态：待开始"
            }
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeRoot(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val scroll = ScrollView(this)
        scroll.setBackgroundResource(R.drawable.app_background)
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        scroll.addView(root)
        setContentView(scroll)
        return root
    }

    private fun label(text: String, size: Float = 16f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, dp(8), 0, dp(5))
        }

    private fun edit(hint: String, value: String, single: Boolean = true): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 16f
            if (single) {
                setSingleLine(true)
            } else {
                minLines = 4
                gravity = Gravity.TOP
            }
        }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

    private fun addField(title: String, input: EditText) {
        root.addView(label(title))
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addInlineField(title: String, input: EditText) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = label(title, 14f).apply {
            gravity = Gravity.CENTER_VERTICAL
            minWidth = dp(112)
            setPadding(0, dp(4), dp(6), dp(4))
        }
        row.addView(titleView, LinearLayout.LayoutParams(dp(112), -2))
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun tokenKey(): SecretKey {
        val alias = "ipspeed_github_token_key"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptToken(token: String): String {
        if (token.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, tokenKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptToken(encryptedToken: String?): String {
        if (encryptedToken.isNullOrBlank()) return ""
        return try {
            val packed = Base64.decode(encryptedToken, Base64.NO_WRAP)
            if (packed.size <= 12) return ""
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, tokenKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun saveGitHubSettings(showToast: Boolean = true): Boolean {
        if (!::githubTokenInput.isInitialized ||
            !::githubUsernameInput.isInitialized ||
            !::githubRepoInput.isInitialized ||
            !::githubBranchInput.isInitialized ||
            !::githubPathInput.isInitialized
        ) return false

        return try {
            val editor = prefs.edit()
                .putString("github_token_enc", encryptToken(githubTokenInput.text.toString().trim()))
                .putString("github_username", githubUsernameInput.text.toString().trim())
                .putString("github_repository", githubRepoInput.text.toString().trim())
                .putString("github_branch", githubBranchInput.text.toString().trim())
                .putString("github_path", githubPathInput.text.toString().trim())
            val ok = editor.commit()
            if (!ok && showToast) Toast.makeText(this, "GitHub 设置保存失败", Toast.LENGTH_LONG).show()
            else if (ok && showToast) Toast.makeText(this, "GitHub 设置已保存", Toast.LENGTH_SHORT).show()
            ok
        } catch (e: Exception) {
            if (showToast) Toast.makeText(this, "GitHub 设置保存失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun loadGitHubConfig(): GitHubConfig = GitHubConfig(
        token = decryptToken(prefs.getString("github_token_enc", "")),
        username = prefs.getString("github_username", "").orEmpty(),
        repository = prefs.getString("github_repository", "").orEmpty(),
        branch = prefs.getString("github_branch", "").orEmpty(),
        path = prefs.getString("github_path", "").orEmpty(),
        mode = prefs.getString("github_mode", "").orEmpty()
    )

    private fun updateGitHubStatusViews() {
        githubAutoStatusView?.text = if (prefs.getBoolean("github_auto_upload", false)) "已启用" else "已关闭"
        githubConnectionStatusView?.text = prefs.getString("github_status", "未检测") ?: "未检测"
        githubLastUploadTimeView?.text = prefs.getString("github_last_upload_time", "--") ?: "--"
        githubLastUploadCountView?.text = prefs.getString("github_last_upload_count", "--") ?: "--"
    }

    private fun currentCopyText(): String {
        val threshold = if (::thresholdInput.isInitialized) {
            thresholdInput.text.toString().toDoubleOrNull() ?: 0.5
        } else {
            0.5
        }
        return if (resultLines.isNotEmpty()) {
            filterResults(resultLines, threshold).joinToString("\n")
        } else {
            prefs.getString("last_completed_result", "").orEmpty()
        }
    }

    private fun setAutoUploadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("github_auto_upload", enabled).commit()
        Toast.makeText(
            this,
            if (enabled) "已启用测速完成后自动上传" else "已关闭自动上传",
            Toast.LENGTH_SHORT
        ).show()
        updateGitHubStatusViews()
    }

    private fun showGitHubSettings() {
        sourcePage = false
        githubPage = true
        makeRoot()
        root.addView(label("GitHub 账号与仓库", 26f))
        root.addView(label("这里只管理连接信息；上传方式在主页面选择。Token 使用 Android Keystore 加密保存。", 13f))

        root.addView(label("账号与仓库信息", 18f))
        githubTokenInput = edit("GitHub Token", decryptToken(prefs.getString("github_token_enc", "")))
        githubTokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        githubUsernameInput = edit("GitHub 用户名", prefs.getString("github_username", "").orEmpty())
        githubRepoInput = edit("仓库名称", prefs.getString("github_repository", "").orEmpty())
        githubBranchInput = edit("分支名称", prefs.getString("github_branch", "").orEmpty())
        githubPathInput = edit("上传文件路径", prefs.getString("github_path", "").orEmpty())
        addInlineField("Token", githubTokenInput)
        addInlineField("用户名", githubUsernameInput)
        addInlineField("仓库", githubRepoInput)
        addInlineField("分支", githubBranchInput)
        addInlineField("文件路径", githubPathInput)

        root.addView(label("上传状态", 18f))
        root.addView(label("自动上传", 13f))
        githubAutoStatusView = label("已关闭", 14f)
        root.addView(githubAutoStatusView)
        root.addView(label("GitHub 上传", 13f))
        githubConnectionStatusView = label("未检测", 14f)
        root.addView(githubConnectionStatusView)
        root.addView(label("最后上传时间", 13f))
        githubLastUploadTimeView = label("--", 14f)
        root.addView(githubLastUploadTimeView)
        root.addView(label("最后上传数量", 13f))
        githubLastUploadCountView = label("--", 14f)
        root.addView(githubLastUploadCountView)
        updateGitHubStatusViews()

        root.addView(label("操作", 18f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("保存设置") { saveGitHubSettings(true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        actions.addView(button("测试上传") {
            if (saveGitHubSettings(false)) startGitHubUpload(currentCopyText(), false)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        root.addView(actions)
        root.addView(button("返回主页面") { showMain() })

        val watchers = listOf(githubTokenInput, githubUsernameInput, githubRepoInput, githubBranchInput, githubPathInput)
        watchers.forEach { input ->
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { saveGitHubSettings(false) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
    }

    private fun startGitHubUpload(content: String, automatic: Boolean) {
        if (content.isBlank()) {
            recordGitHubUpload(false, "上传失败：没有可上传的当前显示结果", 0)
            return
        }
        executor.execute {
            try {
                val count = uploadToGitHub(content)
                recordGitHubUpload(true, if (automatic) "自动上传成功" else "上传成功", count)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                recordGitHubUpload(false, "上传失败：$reason", 0)
            }
        }
    }

    private fun recordGitHubUpload(success: Boolean, message: String, count: Int) {
        val editor = prefs.edit().putString("github_status", message)
        if (success) {
            editor.putString(
                "github_last_upload_time",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )
            editor.putString("github_last_upload_count", count.toString())
        }
        editor.commit()
        runOnUiThread {
            updateGitHubStatusViews()
            Toast.makeText(this, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadToGitHub(content: String): Int {
        val config = loadGitHubConfig()
        if (config.token.isBlank()) throw IOException("请先填写 GitHub Token")
        if (config.username.isBlank()) throw IOException("请填写 GitHub 用户名")
        if (config.repository.isBlank()) throw IOException("请填写仓库名称")
        if (config.branch.isBlank()) throw IOException("请填写分支名称")
        if (config.path.isBlank()) throw IOException("请填写上传文件路径")
        if (config.mode != "overwrite" && config.mode != "append") throw IOException("请先选择覆盖上传或追加上传")

        val encodedPath = config.path.trim('/').split('/').joinToString("/") { Uri.encode(it) }
        val branchQuery = Uri.encode(config.branch)
        val apiUrl = "https://api.github.com/repos/${config.username}/${config.repository}/contents/$encodedPath?ref=$branchQuery"
        var existingContent = ""
        var existingSha: String? = null
        var getConnection: HttpURLConnection? = null
        try {
            getConnection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "IPSpeed-Android")
            }
            val code = getConnection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val json = getConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val file = org.json.JSONObject(json)
                existingSha = file.optString("sha").takeIf { it.isNotBlank() && it != "null" }
                val encoded = file.optString("content", "").replace("\n", "").replace("\r", "")
                if (encoded.isNotBlank()) {
                    existingContent = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                }
            } else if (code != HttpURLConnection.HTTP_NOT_FOUND) {
                val detail = getConnection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IOException("读取 GitHub 文件失败（HTTP $code）：${detail.take(300)}")
            }
        } finally {
            getConnection?.disconnect()
        }

        val newContent = if (config.mode == "append" && existingContent.isNotEmpty()) {
            existingContent + (if (existingContent.endsWith("\n")) "" else "\n") + content
        } else {
            content
        }
        val encodedContent = Base64.encodeToString(newContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = org.json.JSONObject()
            .put("message", if (config.mode == "append") "Append IP speed test results" else "Update IP speed test results")
            .put("content", encodedContent)
            .put("branch", config.branch)
        existingSha?.let { body.put("sha", it) }

        var putConnection: HttpURLConnection? = null
        try {
            putConnection = (URL("https://api.github.com/repos/${config.username}/${config.repository}/contents/$encodedPath").openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "IPSpeed-Android")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            putConnection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = putConnection.responseCode
            if (code !in 200..299) {
                val detail = putConnection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IOException("写入 GitHub 失败（HTTP $code）：${detail.take(400)}")
            }
        } finally {
            putConnection?.disconnect()
        }
        return content.lineSequence().count { it.isNotBlank() }
    }

    private fun attachMainAutoSave(input: EditText) {
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                saveMainSettings()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun currentTargetPreset(): String =
        if (::targetPresetSpinner.isInitialized) {
            when (targetPresetSpinner.selectedItemPosition) {
                0 -> "auto"
                1 -> "cloudflare"
                2 -> "cm"
                3 -> "mobile"
                else -> "custom"
            }
        } else {
            prefs.getString("targetPreset", "auto").orEmpty()
        }

    private fun resolveTargetUrlForCore(): String {
        return when (currentTargetPreset()) {
            "auto" -> defaultTargetUrl
            else -> targetInput.text.toString().trim()
        }
    }

    private fun inferTargetPreset(savedTarget: String): String {
        return when (savedTarget.trim()) {
            "", defaultTargetUrl -> "auto"
            cloudflareTargetUrl -> "cloudflare"
            cmTargetUrl -> "cm"
            mobileTargetUrl -> "mobile"
            else -> "custom"
        }
    }

    private fun setTargetPreset(position: Int, clearCustomInput: Boolean = false) {
        if (!::targetPresetSpinner.isInitialized || !::targetInput.isInitialized) return
        updatingTargetPreset = true
        when (position) {
            0 -> {
                targetInput.setText(autoTargetLabel)
                targetInput.isFocusable = false
                targetInput.isFocusableInTouchMode = false
                targetInput.isCursorVisible = false
            }
            1 -> {
                targetInput.isFocusable = true
                targetInput.isFocusableInTouchMode = true
                targetInput.isCursorVisible = true
                targetInput.setText(cloudflareTargetUrl)
            }
            2 -> {
                targetInput.isFocusable = true
                targetInput.isFocusableInTouchMode = true
                targetInput.isCursorVisible = true
                targetInput.setText(cmTargetUrl)
            }
            3 -> {
                targetInput.isFocusable = true
                targetInput.isFocusableInTouchMode = true
                targetInput.isCursorVisible = true
                targetInput.setText(mobileTargetUrl)
            }
            else -> {
                targetInput.isFocusable = true
                targetInput.isFocusableInTouchMode = true
                targetInput.isCursorVisible = true
                if (clearCustomInput) targetInput.setText("")
                targetInput.requestFocus()
                targetInput.setSelection(targetInput.text.length)
            }
        }
        updatingTargetPreset = false
        prefs.edit()
            .putString("targetPreset", currentTargetPreset())
            .putString("target", resolveTargetUrlForCore())
            .commit()
    }

    private fun resetTargetPreset() {
        targetPresetSpinner.setSelection(0)
        setTargetPreset(0)
        Toast.makeText(this, "测速网址已恢复自动选择", Toast.LENGTH_SHORT).show()
    }

    private fun saveMainSettings(): Boolean {
        return try {
            val ok = prefs.edit()
                .putString("target", resolveTargetUrlForCore())
                .putString("targetPreset", currentTargetPreset())
                .putString("connectN", connectInput.text.toString())
                .putString("downloadN", downloadInput.text.toString())
                .putString("topN", topNInput.text.toString())
                .putString("threshold", thresholdInput.text.toString())
                .commit()
            if (!ok) Toast.makeText(this, "设置保存失败，请检查设备存储空间", Toast.LENGTH_LONG).show()
            ok
        } catch (e: Exception) {
            Toast.makeText(this, "设置保存失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun saveSourceSettings(): Boolean {
        if (!::urlsInput.isInitialized || !::localCheck.isInitialized || !::networkCheck.isInitialized) return false
        return try {
            val ok = prefs.edit()
                .putString("urls", urlsInput.text.toString())
                .putBoolean("localEnabled", localCheck.isChecked)
                .putBoolean("networkEnabled", networkCheck.isChecked)
                .commit()
            if (!ok) Toast.makeText(this, "IP 来源设置保存失败，请检查设备存储空间", Toast.LENGTH_LONG).show()
            ok
        } catch (e: Exception) {
            Toast.makeText(this, "IP 来源设置保存失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun showMain() {
        sourcePage = false
        githubPage = false
        githubAutoStatusView = null
        githubConnectionStatusView = null
        githubLastUploadTimeView = null
        githubLastUploadCountView = null
        makeRoot()

        root.addView(label("IP 测速", 26f))
        root.addView(label("测速参数、IP 来源、上传方式集中管理", 13f))

        root.addView(label("一、测速参数", 18f))
        val savedTarget = prefs.getString("target", defaultTargetUrl).orEmpty()
        val savedPreset = prefs.getString("targetPreset", inferTargetPreset(savedTarget)).orEmpty()
        targetInput = edit("HTTPS 测速地址", savedTarget.ifBlank { defaultTargetUrl })
        targetPresetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                targetPresetLabels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val initialPresetPosition = when (savedPreset) {
            "cloudflare" -> 1
            "cm" -> 2
            "mobile" -> 3
            "custom" -> 4
            else -> 0
        }
        targetPresetSpinner.setSelection(initialPresetPosition, false)
        lastTargetPresetPosition = initialPresetPosition
        setTargetPreset(initialPresetPosition, clearCustomInput = false)
        connectInput = edit("连通性并发数", prefs.getString("connectN", "300")!!)
        downloadInput = edit("下载测速并发数", prefs.getString("downloadN", "100")!!)
        topNInput = edit("参与下载测速的低延迟 IP 数量", prefs.getString("topN", "20")!!)
        thresholdInput = edit("最低显示速度（MiB/s）", prefs.getString("threshold", "0.5")!!)
        root.addView(label("测速网址", 14f))
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        targetRow.addView(targetPresetSpinner, LinearLayout.LayoutParams(0, -2, 1.15f).apply { marginEnd = dp(5) })
        targetRow.addView(targetInput, LinearLayout.LayoutParams(0, -2, 2f).apply { marginEnd = dp(5) })
        targetRow.addView(button("↺") { resetTargetPreset() }, LinearLayout.LayoutParams(dp(48), -2))
        root.addView(targetRow, LinearLayout.LayoutParams(-1, -2))
        targetPresetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingTargetPreset) return
                val previousPosition = lastTargetPresetPosition
                lastTargetPresetPosition = position
                setTargetPreset(position, clearCustomInput = position == 4 && previousPosition != 4)
                saveMainSettings()
                if (position == 4) {
                    targetInput.post {
                        targetInput.requestFocus()
                        targetInput.setSelection(targetInput.text.length)
                        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                            ?.showSoftInput(targetInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
        targetInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!updatingTargetPreset && targetPresetSpinner.selectedItemPosition != 4) {
                    updatingTargetPreset = true
                    lastTargetPresetPosition = 4
                    targetPresetSpinner.setSelection(4)
                    updatingTargetPreset = false
                    prefs.edit().putString("targetPreset", "custom").commit()
                }
                if (!updatingTargetPreset) saveMainSettings()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val concurrencyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val connectBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        connectBox.addView(label("连通性并发数", 14f)); connectBox.addView(connectInput)
        val downloadBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        downloadBox.addView(label("下载测速并发数", 14f)); downloadBox.addView(downloadInput)
        concurrencyRow.addView(connectBox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        concurrencyRow.addView(downloadBox, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(concurrencyRow)
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val topBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBox.addView(label("低延迟 IP 数量", 14f)); topBox.addView(topNInput)
        val thresholdBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        thresholdBox.addView(label("最低显示速度（MiB/s）", 14f)); thresholdBox.addView(thresholdInput)
        filterRow.addView(topBox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        filterRow.addView(thresholdBox, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(filterRow)

        root.addView(label("二、IP 来源", 18f))
        root.addView(label("本地文件导入", 15f))
        val localImportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        localImportRow.addView(button("选择 TXT / CSV 文件") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, 701)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        localImportRow.addView(button("剪切板导入IP") {
            importIPsFromClipboard()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(localImportRow)
        sourceStats = label("已保存唯一 IPv4：${importedIPs.size}", 14f)
        root.addView(sourceStats)
        root.addView(button("清除已导入的本地 IP") {
            importedIPs = emptyList()
            geoRowsByIP = emptyMap()
            countryCodesByIP = emptyMap()
            prefs.edit().putString("imported_ips", "").commit()
            saveGeoMetadata()
            sourceStats.text = "已保存唯一 IPv4：0"
            Toast.makeText(this, "已清除本地 IP", Toast.LENGTH_SHORT).show()
        })
        localCheck = CheckBox(this).apply {
            text = "启用本地文件 IP"
            isChecked = prefs.getBoolean("localEnabled", false)
        }
        networkCheck = CheckBox(this).apply {
            text = "启用网络 URL 来源"
            isChecked = prefs.getBoolean("networkEnabled", true)
        }
        root.addView(localCheck)
        root.addView(networkCheck)
        root.addView(label("网络 URL（每行一个，编辑后自动保存）", 14f))
        urlsInput = edit("每行一个 HTTPS URL", prefs.getString("urls", defaults.joinToString("\\n"))!!, false)
        root.addView(urlsInput, LinearLayout.LayoutParams(-1, dp(140)))
        urlsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { saveSourceSettings() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        localCheck.setOnCheckedChangeListener { _, _ -> saveSourceSettings() }
        networkCheck.setOnCheckedChangeListener { _, _ -> saveSourceSettings() }

        root.addView(label("三、测速操作", 18f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("开始测速") { startTest() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        controls.addView(button("停止测速") { stopTest() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        root.addView(controls)

        root.addView(label("四、GitHub 上传", 18f))
        root.addView(button("账号与仓库设置") { showGitHubSettings() })
        root.addView(label("上传方式", 15f))
        githubModeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val overwrite = RadioButton(this).apply { id = 1001; text = "覆盖上传" }
        val append = RadioButton(this).apply { id = 1002; text = "追加上传" }
        githubModeGroup.addView(overwrite)
        githubModeGroup.addView(append)
        when (prefs.getString("github_mode", "").orEmpty()) {
            "overwrite" -> githubModeGroup.check(1001)
            "append" -> githubModeGroup.check(1002)
            else -> githubModeGroup.clearCheck()
        }
        githubModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) { 1001 -> "overwrite"; 1002 -> "append"; else -> "" }
            prefs.edit().putString("github_mode", mode).commit()
        }
        root.addView(githubModeGroup)
        val uploadActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        uploadActions.addView(button("立即上传") {
            val config = loadGitHubConfig()
            if (config.token.isBlank() || config.username.isBlank() || config.repository.isBlank() || config.branch.isBlank() || config.path.isBlank()) {
                Toast.makeText(this, "请先填写 GitHub 账号与仓库设置", Toast.LENGTH_LONG).show()
                showGitHubSettings()
            } else if (prefs.getString("github_mode", "").orEmpty().isBlank()) {
                Toast.makeText(this, "请先选择覆盖上传或追加上传", Toast.LENGTH_LONG).show()
            } else startGitHubUpload(currentCopyText(), false)
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        uploadActions.addView(android.widget.Switch(this).apply {
            text = "自动上传"
            isChecked = prefs.getBoolean("github_auto_upload", false)
            minWidth = 0
            minimumWidth = 0
            gravity = Gravity.CENTER
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.rgb(40, 120, 232), Color.rgb(139, 146, 156))
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.rgb(105, 160, 235), Color.rgb(190, 194, 200))
            )
            setOnCheckedChangeListener { _, checked -> setAutoUploadEnabled(checked) }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        root.addView(uploadActions)

        val resultHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resultHeaderRow.addView(label("五、测速结果", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        resultHeaderRow.addView(button("复制当前结果") { copyResults() }, LinearLayout.LayoutParams(-2, -2))
        root.addView(resultHeaderRow)
        status = label("状态：待开始", 14f)
        status.setPadding(0, dp(2), 0, dp(8))
        root.addView(status)
        root.addView(label("合格结果（按速度从高到低）", 16f))
        resultsView = label("暂无结果")
        resultsView.textSize = 14f
        resultsView.setPadding(dp(12), dp(12), dp(12), dp(12))
        resultsView.setBackgroundColor(0xFFF1F3F4.toInt())
        root.addView(resultsView, LinearLayout.LayoutParams(-1, dp(320)))
        root.addView(label("数据中心汇总", 16f))
        dataCenterSummaryView = label("暂无数据中心统计", 13f)
        dataCenterSummaryView.setPadding(dp(12), dp(8), dp(12), dp(8))
        root.addView(dataCenterSummaryView)

        listOf(targetInput, connectInput, downloadInput, topNInput, thresholdInput).forEach { attachMainAutoSave(it) }
        thresholdInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val threshold = s?.toString()?.toDoubleOrNull() ?: return
                if (threshold >= 0.5 && resultLines.isNotEmpty()) {
                    val visible = filterResults(resultLines, threshold)
                    resultsView.text = if (visible.isEmpty()) "没有达到显示标准的结果" else visible.joinToString("\n")
                    status.text = "状态：结果筛选中；显示 ${visible.size} 条"
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun showSources() {
        sourcePage = true
        githubPage = false
        makeRoot()
        root.addView(label("IP 来源管理", 26f))
        root.addView(label("本地文件与网络 URL 分区管理；导入规则和去重逻辑保持不变。", 13f))

        root.addView(label("一、本地 IP 文件", 18f))
        root.addView(label("TXT：忽略行首空白，只保留行首 IPv4；CSV：从字段中提取 IPv4。保留同一行的地理信息用于结果显示；IPv6 不支持。", 13f))
        val localImportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        localImportRow.addView(button("选择 TXT / CSV 文件") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, 701)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        localImportRow.addView(button("剪切板导入IP") {
            importIPsFromClipboard()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(localImportRow)
        sourceStats = label("有效唯一 IPv4：${importedIPs.size}；重复数量：—", 14f)
        root.addView(sourceStats)
        root.addView(button("清除已导入的本地 IP") {
            importedIPs = emptyList()
            geoRowsByIP = emptyMap()
            countryCodesByIP = emptyMap()
            prefs.edit().putString("imported_ips", "").apply()
            saveGeoMetadata()
            sourceStats.text = "有效唯一 IPv4：0；重复数量：0"
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
        })

        root.addView(label("二、启用哪些来源", 18f))
        localCheck = CheckBox(this).apply {
            text = "启用已选择的本地文件 IP"
            isChecked = prefs.getBoolean("localEnabled", false)
        }
        networkCheck = CheckBox(this).apply {
            text = "启用网络 URL 来源"
            isChecked = prefs.getBoolean("networkEnabled", true)
        }
        root.addView(localCheck)
        root.addView(networkCheck)

        root.addView(label("三、网络 URL 列表", 18f))
        root.addView(label("每行一个 URL，可直接编辑、添加或删除。编辑时自动保存。", 13f))
        urlsInput = edit("每行一个 HTTPS URL", prefs.getString("urls", defaults.joinToString("\\n"))!!, false)
        root.addView(urlsInput, LinearLayout.LayoutParams(-1, dp(190)))
        urlsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { saveSourceSettings() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        localCheck.setOnCheckedChangeListener { _, _ -> saveSourceSettings() }
        networkCheck.setOnCheckedChangeListener { _, _ -> saveSourceSettings() }

        root.addView(label("四、保存与返回", 18f))
        root.addView(button("保存网络 URL") {
            if (saveSourceSettings()) Toast.makeText(this, "URL 已保存", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("保存设置并返回") {
            prefs.edit()
                .putBoolean("localEnabled", localCheck.isChecked)
                .putBoolean("networkEnabled", networkCheck.isChecked)
                .putString("urls", urlsInput.text.toString())
                .apply()
            showMain()
        })
        root.addView(button("返回（不保存本页设置）") { showMain() })
    }

    @Deprecated("Deprecated in Android API, retained for broad compatibility")
    private fun importIPsFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "剪切板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "剪切板没有可导入的文本", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "状态：正在从剪切板导入 IP"
        Toast.makeText(this, "正在读取剪切板并识别 IPv4…", Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val parsed = parseImported(text, false)
                val previousIPs = importedIPs
                val previousIPSet = previousIPs.toHashSet()
                val overlaps = parsed.first.count { it in previousIPSet }
                val duplicateCount = parsed.second + overlaps
                val mergedIPs = linkedSetOf<String>().apply {
                    addAll(previousIPs)
                    addAll(parsed.first)
                }
                val finalIPs = mergedIPs.toList()
                val finalRows = geoRowsByIP.filterKeys { it in mergedIPs }
                val finalCountries = countryCodesByIP.filterKeys { it in mergedIPs }
                prefs.edit()
                    .putString("imported_ips", finalIPs.joinToString("\n"))
                    .putBoolean("localEnabled", finalIPs.isNotEmpty())
                    .putString("ip_geo_rows", org.json.JSONObject().apply {
                        finalRows.forEach { (ip, row) -> put(ip, row) }
                    }.toString())
                    .putString("ip_country_codes", org.json.JSONObject().apply {
                        finalCountries.forEach { (ip, code) -> put(ip, code) }
                    }.toString())
                    .apply()
                runOnUiThread {
                    importedIPs = finalIPs
                    geoRowsByIP = finalRows
                    countryCodesByIP = finalCountries
                    savedDataLoaded = true
                    if (::sourceStats.isInitialized) {
                        sourceStats.text = "剪切板重复：${duplicateCount}；本次新增：${finalIPs.size - previousIPs.size}；累计保存唯一 IPv4：${finalIPs.size}"
                    }
                    if (::localCheck.isInitialized) localCheck.isChecked = finalIPs.isNotEmpty()
                    status.text = "状态：剪切板导入完成"
                    val addedCount = finalIPs.size - previousIPs.size
                    val message = if (parsed.first.isEmpty()) {
                        "剪切板中没有识别到有效 IPv4；已有 IP 已保留"
                    } else {
                        "剪切板导入完成：新增 ${addedCount} 个，累计 ${finalIPs.size} 个唯一 IPv4，重复 ${duplicateCount} 个"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "状态：剪切板导入失败"
                    Toast.makeText(this, "剪切板导入失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701) return
        if (resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "未选择文件或文件选择已取消", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = data?.data
        if (uri == null) {
            Toast.makeText(this, "文件选择器没有返回文件地址，请重新选择", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "已选择文件，正在后台读取和识别 IP…", Toast.LENGTH_LONG).show()
        status.text = "状态：正在导入文件，请稍候"
        executor.execute {
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw java.io.IOException("无法打开所选文件")
                val name = queryName(uri).lowercase(Locale.ROOT)
                val isCsv = name.endsWith(".csv") || content.lineSequence().firstOrNull()?.contains(",") == true
                val parsed = parseImported(content, isCsv)
                val geo = parseGeoMetadata(content, isCsv)

                val previousIPs = importedIPs
                val previousIPSet = previousIPs.toHashSet()
                val overlaps = parsed.first.count { it in previousIPSet }
                val duplicateCount = parsed.second + overlaps
                val mergedIPs = linkedSetOf<String>().apply {
                    addAll(previousIPs)
                    addAll(parsed.first)
                }

                val mergedRows = geoRowsByIP.toMutableMap()
                val mergedCountries = countryCodesByIP.toMutableMap()
                mergeGeoMetadata(mergedRows, mergedCountries, geo)
                val finalIPs = mergedIPs.toList()
                val finalRows = mergedRows.filterKeys { it in mergedIPs }
                val finalCountries = mergedCountries.filterKeys { it in mergedIPs }

                prefs.edit()
                    .putString("imported_ips", finalIPs.joinToString("\n"))
                    .putBoolean("localEnabled", finalIPs.isNotEmpty())
                    .putString("ip_geo_rows", org.json.JSONObject().apply {
                        finalRows.forEach { (ip, row) -> put(ip, row) }
                    }.toString())
                    .putString("ip_country_codes", org.json.JSONObject().apply {
                        finalCountries.forEach { (ip, code) -> put(ip, code) }
                    }.toString())
                    .apply()

                runOnUiThread {
                    importedIPs = finalIPs
                    geoRowsByIP = finalRows
                    countryCodesByIP = finalCountries
                    savedDataLoaded = true
                    if (!::sourceStats.isInitialized) showSources()
                    if (::sourceStats.isInitialized) {
                        sourceStats.text = "本次文件重复：$duplicateCount；本次新增：${finalIPs.size - previousIPs.size}；累计保存唯一 IPv4：${finalIPs.size}"
                    }
                    if (::localCheck.isInitialized) localCheck.isChecked = finalIPs.isNotEmpty()
                    status.text = "状态：文件导入完成"
                    val addedCount = finalIPs.size - previousIPs.size
                    val message = if (parsed.first.isEmpty()) {
                        "文件已读取，但没有识别到有效 IPv4；已有 IP 已保留"
                    } else {
                        "累计导入完成：本次新增 $addedCount 个，累计保存 ${finalIPs.size} 个唯一 IPv4，重复 $duplicateCount 个"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "状态：文件导入失败"
                    Toast.makeText(this, "读取文件失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment ?: ""
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && index >= 0) name = c.getString(index) ?: name
            }
        } catch (_: Exception) {}
        return name
    }

    private fun validIPv4(value: String): String? {
        val parts = value.split(".")
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it !in 0..255 }) return null
        return nums.joinToString(".")
    }

    private fun readStringMap(raw: String?): Map<String, String> {
        return try {
            val json = org.json.JSONObject(raw ?: "{}")
            val result = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (value.isNotEmpty()) result[key] = value
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveGeoMetadata() {
        try {
            val rowsJson = org.json.JSONObject()
            geoRowsByIP.forEach { (ip, row) -> rowsJson.put(ip, row) }
            val countriesJson = org.json.JSONObject()
            countryCodesByIP.forEach { (ip, code) -> countriesJson.put(ip, code) }
            prefs.edit()
                .putString("ip_geo_rows", rowsJson.toString())
                .putString("ip_country_codes", countriesJson.toString())
                .apply()
        } catch (e: Exception) {
            Toast.makeText(this, "地理信息保存失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun splitCsvFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    fields.add(field.toString().trim())
                    field.setLength(0)
                }
                else -> field.append(c)
            }
            i++
        }
        fields.add(field.toString().trim())
        return fields
    }

    private fun normalizeCountryCode(value: String?): String? {
        val code = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        return code.takeIf { Regex("^[A-Z]{2}$").matches(it) }
    }

    private fun countryCodeFromLine(line: String, csv: Boolean, countryIndex: Int?): String? {
        // 不再从文件或网络来源内容中解析国家代码；国家/地区与数据中心信息由测速响应提供。
        return null
    }

    private fun findCountryCodeAnywhere(line: String): String? {
        // ISO 3166-1 alpha-2 国家/地区代码，以及常见的 UK、XK 别名。
        val validCodes = """
            AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ
            BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ
            CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ
            DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR
            GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY
            HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP
            KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU
            LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT
            MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM
            PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW
            SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY
            SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG
            UM US UY UZ VA VC VE VG VI VN VU WF WS XK YE YT ZA ZM ZW
            UK
        """.trimIndent().split(Regex("\\s+")).toSet()

        // 仅匹配独立的两位字母代码，避免把 USA、speed 等普通单词内部字母误判。
        val codeRegex = Regex("(?i)(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])")
        for (match in codeRegex.findAll(line)) {
            val code = match.groupValues[1].uppercase(Locale.ROOT)
            if (code in validCodes) return code
        }
        return null
    }

    private fun parseGeoMetadata(content: String, csv: Boolean): GeoMetadata {
        val firstLine = content.lineSequence().firstOrNull()
        val headers = if (csv) firstLine?.let { splitCsvFields(it) } else null
        val countryAliases = setOf(
            "country", "country_code", "countrycode", "country_iso", "country_iso2",
            "country_iso_code", "iso2", "iso_2", "iso_alpha2", "iso-alpha2", "cc"
        )
        val countryIndex = headers?.indexOfFirst {
            it.trim().trim('"').lowercase(Locale.ROOT).replace(' ', '_') in countryAliases
        }?.takeIf { it >= 0 }
        val rows = linkedMapOf<String, String>()
        val countries = linkedMapOf<String, String>()
        val ipRegex = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")

        for ((lineIndex, line) in content.lineSequence().withIndex()) {
            val ips = parseImported(line, csv).first
            if (ips.isEmpty()) continue
            val country = countryCodeFromLine(
                line,
                csv,
                if (lineIndex == 0 && countryIndex != null) null else countryIndex
            )
            for (ip in ips) {
                // Do not persist a redundant metadata row for plain-IP TXT lines;
                // large IP-only lists should not inflate SharedPreferences with tens of thousands of copies.
                val plainIPOrCIDRLine = Regex("^\\s*[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?:\\s*/\\s*[0-9]{1,2})?\\s*$").matches(line)
                val hasExtraMetadata = csv || !plainIPOrCIDRLine
                if (hasExtraMetadata && (!rows.containsKey(ip) ||
                    (countries[ip].isNullOrBlank() && !country.isNullOrBlank()))) {
                    rows[ip] = line
                }
                if (countries[ip].isNullOrBlank() && !country.isNullOrBlank()) {
                    countries[ip] = country
                }
            }
        }
        return GeoMetadata(rows, countries)
    }

    private fun mergeGeoMetadata(
        targetRows: MutableMap<String, String>,
        targetCountries: MutableMap<String, String>,
        incoming: GeoMetadata
    ) {
        for ((ip, row) in incoming.rows) {
            val newCountry = incoming.countries[ip]
            if (!targetRows.containsKey(ip) ||
                (targetCountries[ip].isNullOrBlank() && !newCountry.isNullOrBlank())) {
                targetRows[ip] = row
            }
            if (targetCountries[ip].isNullOrBlank() && !newCountry.isNullOrBlank()) {
                targetCountries[ip] = newCountry
            }
        }
    }

    // 单个 CIDR 最多展开 1,048,576 个地址，避免 /8、/0 等超大网段一次性耗尽手机内存。
    private val maxCIDRExpansion = 1_048_576L

    private fun expandIPv4Token(token: String): List<String> {
        val normalizedToken = token.trim().replace(Regex("\\s*/\\s*"), "/")
        val parts = normalizedToken.split("/", limit = 2)
        val baseIP = validIPv4(parts[0]) ?: return emptyList()
        if (parts.size == 1) return listOf(baseIP)

        val prefix = parts[1].toIntOrNull() ?: return emptyList()
        if (prefix !in 0..32) return emptyList()

        val octets = baseIP.split(".").map { it.toLong() }
        val value = (octets[0] shl 24) or (octets[1] shl 16) or
            (octets[2] shl 8) or octets[3]
        val hostBits = 32 - prefix
        val count = 1L shl hostBits
        if (count > maxCIDRExpansion) {
            throw IOException("CIDR 网段地址数量过大（$count），单个网段最多展开 $maxCIDRExpansion 个；请拆分成较小网段后导入")
        }
        val mask = if (prefix == 0) 0L else (0xFFFF_FFFFL shl hostBits) and 0xFFFF_FFFFL
        val network = value and mask
        val result = ArrayList<String>(count.toInt())
        var current = network
        repeat(count.toInt()) {
            result.add(
                listOf(
                    (current ushr 24) and 255,
                    (current ushr 16) and 255,
                    (current ushr 8) and 255,
                    current and 255
                ).joinToString(".")
            )
            current++
        }
        return result
    }

    private fun parseImported(content: String, csv: Boolean): Pair<List<String>, Int> {
        val found = mutableListOf<String>()
        val tokenRegex = Regex("(?<![0-9.])([0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?:\\s*/\\s*[0-9]{1,2})?)(?![0-9./])")
        for (line in content.lineSequence()) {
            val tokens = if (csv) {
                tokenRegex.findAll(line).map { it.groupValues[1] }.toList()
            } else {
                val trimmed = line.trimStart()
                val match = Regex("^([0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?:\\s*/\\s*[0-9]{1,2})?)").find(trimmed)
                    ?: continue
                listOf(match.groupValues[1])
            }
            for (token in tokens) found.addAll(expandIPv4Token(token))
        }
        val unique = linkedSetOf<String>()
        var duplicates = 0
        for (ip in found) {
            if (!unique.add(ip)) duplicates++
        }
        return unique.toList() to duplicates
    }

    private fun startTest() {
        if (!savedDataLoaded) {
            Toast.makeText(this, "本地 IP 数据仍在加载，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (process != null) {
            Toast.makeText(this, "测速正在运行", Toast.LENGTH_SHORT).show()
            return
        }
        val target = resolveTargetUrlForCore()
        val connectN = connectInput.text.toString().trim().toIntOrNull()
        val downloadN = downloadInput.text.toString().trim().toIntOrNull()
        val topN = topNInput.text.toString().trim().toIntOrNull()
        val threshold = thresholdInput.text.toString().trim().toDoubleOrNull()
        if (!target.startsWith("https://") || connectN == null || connectN <= 0 ||
            downloadN == null || downloadN <= 0 || topN == null || topN <= 0 ||
            threshold == null || threshold < 0.5) {
            Toast.makeText(this, "请检查参数；最低显示速度不能低于核心固定的 0.5 MiB/s", Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit()
            .putString("target", target)
            .putString("targetPreset", currentTargetPreset())
            .putString("connectN", connectN.toString())
            .putString("downloadN", downloadN.toString())
            .putString("topN", topN.toString())
            .putString("threshold", threshold.toString())
            .apply()

        val localEnabled = prefs.getBoolean("localEnabled", true)
        val networkEnabled = prefs.getBoolean("networkEnabled", true)
        val clipboardText = if (localEnabled) {
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() else ""
            } catch (_: Exception) {
                ""
            }
        } else ""
        val urls = prefs.getString("urls", defaults.joinToString("\n"))!!
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (!localEnabled && !networkEnabled) {
            Toast.makeText(this, "请至少启用一个 IP 来源", Toast.LENGTH_LONG).show()
            return
        }
        if (localEnabled && importedIPs.isEmpty() && !networkEnabled) {
            Toast.makeText(this, "本地来源已启用，但还没有导入 IP 文件；请启用网络来源或导入文件", Toast.LENGTH_LONG).show()
            return
        }

        val binary = File(filesDir, "sss")
        val ipFile = File(filesDir, "ips.txt")
        try {
            if (!binary.exists()) assets.open("sss").use { input ->
                FileOutputStream(binary).use { output -> input.copyTo(output) }
            }
            binary.setExecutable(true, true)
        } catch (e: Exception) {
            Toast.makeText(this, "准备测速文件失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Android 原生网络栈读取 IP 来源，避免 Go 纯 Go DNS 解析器误用 ::1:53。
        val args = mutableListOf(
            binary.absolutePath, target, connectN.toString(), downloadN.toString(), topN.toString(), "no"
        )
        resultLines = emptyList()
        resultsView.text = "正在准备测速…"
        status.text = "状态：正在启动"
        process = null
        val savedGeoRows = geoRowsByIP.toMap()
        val savedCountryCodes = countryCodesByIP.toMap()
        executor.execute {
            val sourceLog = StringBuilder()
            val runGeoRows = linkedMapOf<String, String>()
            val runCountryCodes = linkedMapOf<String, String>()
            try {
                // 再次规范化本地保存内容，防止旧版本保存的重复/非规范 IP 混入测速。
                val mergedIPs = linkedSetOf<String>()
                if (localEnabled) {
                    val clipboardParsed = if (clipboardText.isNotBlank()) parseImported(clipboardText, false).first else emptyList()
                    val localUnique = linkedSetOf<String>().apply {
                        addAll(parseImported(importedIPs.joinToString("\n"), false).first)
                        addAll(clipboardParsed)
                    }.toList()
                    mergedIPs.addAll(localUnique)
                    for (ip in localUnique) {
                        savedGeoRows[ip]?.let { runGeoRows[ip] = it }
                        savedCountryCodes[ip]?.let { runCountryCodes[ip] = it }
                    }
                    sourceLog.append("本地来源：原保存 ${importedIPs.size} 条；重新去重后加入 ${localUnique.size} 个唯一 IPv4\n")
                } else {
                    sourceLog.append("本地来源：未启用\n")
                }

                if (networkEnabled) {
                    for (sourceURL in urls) {
                        sourceLog.append("正在读取：${sourceURL}\n")
                        runOnUiThread {
                            status.text = "状态：正在读取 IP 来源"
                            resultsView.text = sourceLog.toString()
                        }
                        var connection: HttpURLConnection? = null
                        try {
                            connection = (URL(sourceURL).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 15000
                                readTimeout = 60000
                                instanceFollowRedirects = true
                                setRequestProperty("User-Agent", "Mozilla/5.0")
                            }
                            val code = connection.responseCode
                            if (code !in 200..299) throw java.io.IOException("HTTP 状态码：$code")
                            val content = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val found = parseImported(content, false).first
                            val geo = parseGeoMetadata(content, false)
                            mergeGeoMetadata(runGeoRows, runCountryCodes, geo)
                            val before = mergedIPs.size
                            mergedIPs.addAll(found)
                            sourceLog.append("该来源识别到 ${found.size} 个唯一 IPv4；新增 ${mergedIPs.size - before} 个\n")
                        } catch (e: Exception) {
                            sourceLog.append("读取失败，跳过此来源：${e.message ?: e.javaClass.simpleName}\n")
                        } finally {
                            connection?.disconnect()
                        }
                    }
                } else {
                    sourceLog.append("网络来源：未启用\n")
                }

                if (mergedIPs.isEmpty()) {
                    runOnUiThread {
                        process = null
                        resultsView.text = sourceLog.toString() + "读取 IP 来源失败：所有来源均未取得有效 IPv4"
                        status.text = "状态：IP 来源读取失败"
                    }
                    return@execute
                }

                // 写入前最后一次规范化去重；Go 核心读取的就是这份文件。
                val finalIPs = parseImported(mergedIPs.joinToString("\n"), false).first
                ipFile.writeText(finalIPs.joinToString("\n") + "\n")
                sourceLog.append("全部来源合并去重后：${finalIPs.size} 个唯一 IPv4\n")
                sourceLog.append("最终写入 ips.txt、交给测速核心：${finalIPs.size} 个 IP\n\n")
                runOnUiThread {
                    status.text = "状态：即将测速，共 ${finalIPs.size} 个唯一 IP"
                    resultsView.text = sourceLog.toString()
                }
                val p = ProcessBuilder(args).directory(filesDir).redirectErrorStream(true).start()
                process = p
                runOnUiThread { status.text = "状态：测速进行中" }
                val all = StringBuilder()
                var processedLineCount = 0
                var latestProgressLine = ""
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        all.append(line).append('\n')
                        processedLineCount++
                        latestProgressLine = line.takeLast(160)
                        // 合并进度刷新：避免大量 IP 输出时每一行日志都抢占主线程。
                        // 完整日志仍保留，任务结束后统一显示；测速判定和结果不变。
                        if (processedLineCount == 1 || processedLineCount % 25 == 0) {
                            val progress = latestProgressLine
                            runOnUiThread {
                                status.text = "状态：测速进行中\n$progress"
                            }
                        }
                    }
                }
                val exitCode = p.waitFor()
                val parsedRaw = all.toString().lineSequence()
                    .map { it.trim() }
                    .filter { line ->
                        val first = line.substringBefore("|")
                        Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}#[0-9]+(?:\\.[0-9]+)?M$").matches(first) &&
                            line.contains("|")
                    }
                    .toList()
                val parsed = parsedRaw.map { raw ->
                    val fields = raw.split("|")
                    val baseLine = fields[0]
                    val ip = baseLine.substringBefore("#")
                    val speed = baseLine.substringAfter("#").removeSuffix("M")
                    val colo = fields.getOrNull(1).orEmpty().ifBlank { "UNK" }
                    val sourceLocation = fields.getOrNull(3).orEmpty().ifBlank { "XX" }
                    "$ip:443#$colo-$sourceLocation-${speed}MB/s"
                }
                val dcStats = parsedRaw.map { raw ->
                    val fields = raw.split("|")
                    val baseLine = fields[0]
                    val ip = baseLine.substringBefore("#")
                    val colo = fields.getOrNull(1).orEmpty().ifBlank { "UNK" }
                    val country = fields.getOrNull(2).orEmpty().ifBlank { "XX" }
                    val latency = fields.getOrNull(4)?.toLongOrNull() ?: Long.MAX_VALUE
                    val region = fields.getOrNull(5).orEmpty()
                    val city = fields.getOrNull(6).orEmpty()
                    listOf(colo, country, latency.toString(), region, city)
                }.filter { it[0] != "UNK" }
                val dcSummary = dcStats.groupBy { it[0] }.entries.sortedBy { it.key }.map { (colo, rows) ->
                    val first = rows.first()
                    val validLatencies = rows.mapNotNull { it[2].toLongOrNull() }.filter { it != Long.MAX_VALUE }
                    val minLatency = validLatencies.minOrNull()?.let { "${it} ms" } ?: "未知"
                    val location = listOf(first[3], first[4]).filter { it.isNotBlank() }.distinct().joinToString(" / ")
                    "$colo-${first[1]}：${rows.size} 个 IP；最低延迟 $minLatency" +
                        if (location.isNotBlank()) "；$location" else ""
                }
                resultLines = parsed
                val outputText = sourceLog.toString() + all.toString().trim()
                runOnUiThread {
                    process = null
                    val visible = filterResults(parsed, threshold)
                    dataCenterSummaryView.text = if (dcSummary.isEmpty()) "暂无可识别的数据中心；请检查网络或映射数据" else dcSummary.joinToString("\n")
                    if (exitCode != 0 || parsed.isEmpty()) {
                        resultsView.text = outputText.takeLast(6000).ifBlank {
                            "测速程序没有输出日志。请检查 IP 来源设置和测速地址。"
                        }
                        status.text = if (exitCode == 0) {
                            "状态：程序已结束，但没有识别到测速结果"
                        } else {
                            "状态：测速程序异常退出（代码 $exitCode），详细日志见下方"
                        }
                    } else {
                        resultsView.text = if (visible.isEmpty()) "没有达到显示标准的结果" else visible.joinToString("\n")
                        val snapshot = visible.joinToString("\n")
                        val saved = try { prefs.edit().putString("last_completed_result", snapshot).commit() } catch (_: Exception) { false }
                        if (!saved) Toast.makeText(this, "最近一次测速结果保存失败", Toast.LENGTH_LONG).show()
                        status.text = "状态：完成；结果 ${visible.size} 条"
                        if (prefs.getBoolean("github_auto_upload", false) && snapshot.isNotBlank()) {
                            startGitHubUpload(snapshot, true)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    process = null
                    status.text = "状态：启动失败"
                    resultsView.text = e.toString()
                }
            }
        }
    }

    private fun resultSpeed(line: String): Double {
        val formatted = Regex("#[A-Z]{3}-[A-Z]{2}-([0-9]+(?:\\.[0-9]+)?)MB/s$").find(line)
        if (formatted != null) return formatted.groupValues[1].toDoubleOrNull() ?: 0.0
        return line.substringAfter("#").substringBefore("M").toDoubleOrNull() ?: 0.0
    }

    private fun filterResults(lines: List<String>, threshold: Double): List<String> =
        lines.filter { resultSpeed(it) >= threshold }
            .sortedByDescending { resultSpeed(it) }

    private fun stopTest() {
        val p = process
        if (p == null) {
            status.text = "状态：当前没有运行中的测速任务"
            return
        }
        p.destroy()
        process = null
        status.text = "状态：已请求停止"
    }

    private fun copyResults() {
        val text = currentCopyText()
        if (text.isBlank()) {
            Toast.makeText(this, "没有可复制的结果", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("测速结果", text))
        Toast.makeText(this, "已复制 ${text.lines().size} 条结果", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        when {
            githubPage -> showMain()
            sourcePage -> showMain()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        process?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
