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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
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
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

open class MainActivity : Activity() {
    companion object {
        @Volatile private var activeTestOwner: MainActivity? = null

        @Synchronized
        private fun reserveTestTask(owner: MainActivity): Boolean {
            if (activeTestOwner != null) return false
            activeTestOwner = owner
            return true
        }

        @Synchronized
        private fun releaseTestTask(owner: MainActivity) {
            if (activeTestOwner === owner) activeTestOwner = null
        }
    }

    protected open val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val sharedUploadPrefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    protected open val isOfficialMode: Boolean = false
    private val executor = Executors.newSingleThreadExecutor()
    private val testLock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var testGeneration: Long = 0L
    @Volatile private var testActive = false
    private var testFuture: Future<*>? = null
    @Volatile private var activeSourceConnection: HttpURLConnection? = null
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

    private data class OfficialCandidate(
        val ip: String,
        val colo: String,
        val country: String,
        val sourceLocation: String,
        val latencyMs: Double,
        val region: String,
        val city: String
    )

    private data class IPv4CidrRange(
        val cidr: String,
        val network: Long,
        val count: Int
    )

    private val cloudflareIPv4RangesUrl = "https://www.cloudflare.com/ips-v4"
    private val officialRandomIPCount = 6_534

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var resultsView: TextView
    private lateinit var dataCenterSummaryView: TextView
    private lateinit var dataCenterRowsContainer: LinearLayout
    private var officialDataCenterScroll: ScrollView? = null
    private var officialQualifiedByDataCenter: Map<String, List<OfficialCandidate>> = emptyMap()
    private lateinit var targetInput: EditText
    private lateinit var targetPresetSpinner: Spinner
    private var updatingTargetPreset = false
    private var lastTargetPresetPosition = -1
    private lateinit var connectInput: EditText
    private lateinit var downloadInput: EditText
    private lateinit var topNInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var downloadCapInput: EditText
    private lateinit var downloadCapEnabledCheck: CheckBox
    private lateinit var urlsInput: EditText
    private lateinit var localCheck: CheckBox
    private lateinit var networkCheck: CheckBox
    private lateinit var sourceStats: TextView
    private lateinit var officialAutoSpeedCheck: CheckBox
    private lateinit var officialDataCenterInput: EditText

    private val legacyBuiltInSourceURLs = setOf(
        "https://zip.cm.edu.kg/all.txt",
        "https://countrymerge.pages.dev/all.txt"
    )

    private fun savedNetworkURLs(): String {
        val saved = prefs.getString("urls", "").orEmpty()
        val filtered = saved.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in legacyBuiltInSourceURLs }
            .joinToString("\n")
        if (filtered != saved) prefs.edit().putString("urls", filtered).commit()
        return filtered
    }

    private val targetPresetLabels = arrayOf("自动选择", "Cloudflare", "CM提供", "移动专属", "手动输入")
    private val autoTargetLabel = "根据运营商选择"
    private val defaultTargetUrl = "https://speed.cloudflare.com/__down?bytes=50000000"
    private val cloudflareTargetUrl = "https://speed.cloudflare.com/__down?bytes=99999999"
    private val cmTargetUrl = "https://cf.090227.xyz/__down?bytes=99999999"
    private val mobileTargetUrl = "https://speed.okl.abrdns.com"

    private var savedDataLoaded = false

    private data class MainPageSnapshot(
        val statusText: String,
        val resultText: CharSequence,
        val resultTextSize: Float,
        val dataCenterSummaryText: String,
        val showingOfficialDataCenters: Boolean,
        val scrollY: Int
    )

    private var mainPageSnapshot: MainPageSnapshot? = null

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

    private fun modeButton(text: String, selected: Boolean, action: () -> Unit): Button =
        button(text, action).apply {
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(if (selected) Color.WHITE else Color.rgb(55, 65, 81))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(if (selected) Color.rgb(33, 150, 243) else Color.rgb(235, 238, 242))
                setStroke(dp(1), if (selected) Color.rgb(33, 150, 243) else Color.rgb(210, 215, 222))
            }
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
        mode = sharedUploadPrefs.getString("github_mode", "").orEmpty()
    )

    private fun updateGitHubStatusViews() {
        githubAutoStatusView?.text = if (sharedUploadPrefs.getBoolean("github_auto_upload", false)) "已启用" else "已关闭"
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
        sharedUploadPrefs.edit().putBoolean("github_auto_upload", enabled).commit()
        Toast.makeText(
            this,
            if (enabled) "已启用测速完成后自动上传" else "已关闭自动上传",
            Toast.LENGTH_SHORT
        ).show()
        updateGitHubStatusViews()
    }

    private fun showGitHubSettings() {
        if (::status.isInitialized && ::resultsView.isInitialized && ::dataCenterSummaryView.isInitialized) {
            mainPageSnapshot = MainPageSnapshot(
                statusText = status.text.toString(),
                resultText = resultsView.text,
                resultTextSize = resultsView.textSize,
                dataCenterSummaryText = dataCenterSummaryView.text.toString(),
                showingOfficialDataCenters = isOfficialMode && officialDataCenterScroll?.visibility == View.VISIBLE,
                scrollY = (root.parent as? ScrollView)?.scrollY ?: 0
            )
        }
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
            "auto" -> autoTargetLabel
            else -> targetInput.text.toString().trim()
        }
    }

    private fun normalizeISPName(value: String): String? = when {
        value.contains("移动") -> "中国移动"
        value.contains("联通") -> "中国联通"
        value.contains("电信") -> "中国电信"
        else -> null
    }

    private fun findISPInJson(value: Any?): String? {
        when (value) {
            is org.json.JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.equals("isp", ignoreCase = true) || key.contains("operator", ignoreCase = true)) {
                        val raw = value.opt(key)
                        if (raw is String) normalizeISPName(raw)?.let { return it }
                    }
                }
                val nestedKeys = value.keys()
                while (nestedKeys.hasNext()) {
                    findISPInJson(value.opt(nestedKeys.next()))?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) {
                    findISPInJson(value.opt(index))?.let { return it }
                }
            }
            is String -> return normalizeISPName(value)
        }
        return null
    }

    private fun queryCurrentISPOnce(): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("https://ip9.com.cn/get").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            activeSourceConnection = connection
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("运营商查询请求失败：HTTP $code")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val fromJson = try {
                findISPInJson(org.json.JSONTokener(body).nextValue())
            } catch (_: Exception) {
                null
            }
            fromJson?.let { return it }

            val patterns = listOf(
                Regex("中国移动|移动通信") to "中国移动",
                Regex("中国联通|联通网络") to "中国联通",
                Regex("中国电信|电信网络") to "中国电信"
            )
            for ((pattern, name) in patterns) {
                if (pattern.containsMatchIn(body)) return name
            }
            throw IOException("未查询到运营商名称，无法识别当前运营商")
        } catch (e: Exception) {
            if (e is IOException && e.message?.contains("运营商") == true) throw e
            throw IOException("运营商查询失败：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection?.disconnect()
            if (activeSourceConnection === connection) activeSourceConnection = null
        }
    }

    private fun replaceTargetBytesParameter(url: String, bytes: Long): String {
        val pattern = Regex("([?&]bytes=)[^&#]*", RegexOption.IGNORE_CASE)
        return pattern.replace(url) { match -> "${match.groupValues[1]}$bytes" }
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
                .putString("download_cap_mib", if (::downloadCapInput.isInitialized) downloadCapInput.text.toString() else prefs.getString("download_cap_mib", "1").orEmpty())
                .putBoolean("download_cap_enabled", if (::downloadCapEnabledCheck.isInitialized) downloadCapEnabledCheck.isChecked else prefs.getBoolean("download_cap_enabled", true))
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

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(label("IP 测速", 26f), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(modeButton("个性优选", !isOfficialMode) {
            if (isOfficialMode) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }, LinearLayout.LayoutParams(dp(88), -2).apply { marginStart = dp(4) })
        titleRow.addView(modeButton("官方优选", isOfficialMode) {
            if (!isOfficialMode) {
                startActivity(Intent(this, OfficialActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }, LinearLayout.LayoutParams(dp(88), -2).apply { marginStart = dp(4) })
        root.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(label(if (isOfficialMode) "测速参数与上传方式集中管理" else "测速参数、IP 来源、上传方式集中管理", 13f))

        root.addView(label("一、测速操作", 18f))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val startColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        startColumn.addView(button(if (isOfficialMode) "开始连通性测试" else "开始测速") {
            if (isOfficialMode) startTest(connectivityOnly = true) else startTest()
        }, LinearLayout.LayoutParams(-1, -2))
        if (isOfficialMode) {
            officialAutoSpeedCheck = CheckBox(this).apply {
                text = ""
                textSize = 15f
                isChecked = prefs.getBoolean("official_auto_speed_enabled", false)
                setPadding(0, 0, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("official_auto_speed_enabled", checked).commit()
                }
            }
        }
        val stopColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stopColumn.addView(button("停止测速") { stopTest() }, LinearLayout.LayoutParams(-1, -2))
        controls.addView(startColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        controls.addView(stopColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        root.addView(controls)

        if (isOfficialMode) {
            val optionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val speedOption = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            speedOption.addView(label("开始测速", 16f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), dp(2), dp(4))
            }, LinearLayout.LayoutParams(-2, -2))
            speedOption.addView(officialAutoSpeedCheck, LinearLayout.LayoutParams(-2, -2))
            optionRow.addView(speedOption, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })

            val dataCenterRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            dataCenterRow.addView(label("数据中心", 16f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), dp(4), dp(4))
            }, LinearLayout.LayoutParams(-2, -2))
            officialDataCenterInput = edit(
                "留空则选择最低延迟",
                prefs.getString("official_auto_speed_datacenter", "").orEmpty()
            ).apply {
                textSize = 15f
                setSingleLine(true)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        prefs.edit().putString("official_auto_speed_datacenter", s?.toString().orEmpty()).apply()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            dataCenterRow.addView(officialDataCenterInput, LinearLayout.LayoutParams(0, -2, 1f))
            optionRow.addView(dataCenterRow, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(optionRow, LinearLayout.LayoutParams(-1, -2))
        }

        val resultHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resultHeaderRow.addView(label("二、测速结果", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        resultHeaderRow.addView(button("复制当前结果") { copyResults() }, LinearLayout.LayoutParams(-2, -2))
        root.addView(resultHeaderRow)
        status = label("状态：待开始", 14f)
        status.setPadding(0, dp(2), 0, dp(8))
        root.addView(status)
        val qualifiedHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        qualifiedHeaderRow.addView(
            label(if (isOfficialMode) "合格结果（按数据中心分类）" else "合格结果（按速度从高到低）", 16f),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        if (isOfficialMode) {
            qualifiedHeaderRow.addView(button("返回数据中心") {
                showOfficialDataCenterList()
            }, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(qualifiedHeaderRow)

        resultsView = label("暂无结果")
        resultsView.textSize = 14f
        resultsView.setPadding(dp(2), dp(2), dp(2), dp(2))
        val resultsFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFF1F3F4.toInt())
        }
        resultsFrame.addView(resultsView, LinearLayout.LayoutParams(-1, 0, 1f))
        if (isOfficialMode) {
            dataCenterRowsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            officialDataCenterScroll = ScrollView(this).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                visibility = View.GONE
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            }
            officialDataCenterScroll?.addView(dataCenterRowsContainer, android.view.ViewGroup.LayoutParams(-1, -2))
            resultsFrame.addView(officialDataCenterScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(resultsFrame, LinearLayout.LayoutParams(-1, dp(320)))
        dataCenterSummaryView = label("暂无数据中心统计", 13f)
        dataCenterSummaryView.setPadding(dp(12), dp(8), dp(12), dp(8))
        // Keep summary data internally, but do not render a data-center summary below the gray results box.


        val parameterHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        parameterHeaderRow.addView(label("三、测速参数", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        val downloadCapRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        downloadCapEnabledCheck = CheckBox(this).apply {
            text = "启用"
            isChecked = prefs.getBoolean("download_cap_enabled", true)
            setPadding(0, 0, 0, 0)
        }
        downloadCapInput = edit("5", prefs.getString("download_cap_mib", "5").orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 14f
        }
        downloadCapRow.addView(downloadCapEnabledCheck, LinearLayout.LayoutParams(-2, -2))
        downloadCapRow.addView(label("下载上限", 14f).apply {
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }, LinearLayout.LayoutParams(-2, -2))
        downloadCapRow.addView(downloadCapInput, LinearLayout.LayoutParams(dp(58), -2).apply {
            marginStart = dp(2)
        })
        downloadCapRow.addView(label("MiB", 13f).apply {
            setPadding(dp(2), dp(4), 0, dp(4))
        }, LinearLayout.LayoutParams(-2, -2))
        parameterHeaderRow.addView(downloadCapRow, LinearLayout.LayoutParams(-2, -2))
        root.addView(parameterHeaderRow, LinearLayout.LayoutParams(-1, -2))
        downloadCapEnabledCheck.setOnCheckedChangeListener { _, _ -> saveMainSettings() }

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
        connectInput = edit("连通性并发数", prefs.getString("connectN", "1000")!!)
        downloadInput = edit("下载测速并发数", prefs.getString("downloadN", "3")!!)
        topNInput = edit("参与下载测速的低延迟 IP 数量", prefs.getString("topN", "10")!!)
        thresholdInput = edit("最低显示速度（MiB/s）", prefs.getString("threshold", "0.5")!!)
        root.addView(label("测速网址", 14f))
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        targetRow.addView(targetPresetSpinner, LinearLayout.LayoutParams(0, -2, 1.15f).apply { marginEnd = dp(5) })
        targetRow.addView(targetInput, LinearLayout.LayoutParams(0, -2, 2f).apply { marginEnd = dp(5) })
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

        if (!isOfficialMode) {
            root.addView(label("四、IP 来源", 18f))
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
            urlsInput = edit("每行一个 HTTPS URL", savedNetworkURLs(), false)
            root.addView(urlsInput, LinearLayout.LayoutParams(-1, dp(140)))
            urlsInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { saveSourceSettings() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            localCheck.setOnCheckedChangeListener { _, _ -> saveSourceSettings() }
            networkCheck.setOnCheckedChangeListener { _, _ -> saveSourceSettings() }
        }




        root.addView(label(if (isOfficialMode) "四、GitHub 上传" else "五、GitHub 上传", 18f))
        root.addView(button("账号与仓库设置") { showGitHubSettings() })
        root.addView(label("上传方式", 15f))
        githubModeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val overwrite = RadioButton(this).apply { id = 1001; text = "覆盖上传" }
        val append = RadioButton(this).apply { id = 1002; text = "追加上传" }
        githubModeGroup.addView(overwrite)
        githubModeGroup.addView(append)
        when (sharedUploadPrefs.getString("github_mode", "").orEmpty()) {
            "overwrite" -> githubModeGroup.check(1001)
            "append" -> githubModeGroup.check(1002)
            else -> githubModeGroup.clearCheck()
        }
        githubModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) { 1001 -> "overwrite"; 1002 -> "append"; else -> "" }
            sharedUploadPrefs.edit().putString("github_mode", mode).commit()
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
            } else if (sharedUploadPrefs.getString("github_mode", "").orEmpty().isBlank()) {
                Toast.makeText(this, "请先选择覆盖上传或追加上传", Toast.LENGTH_LONG).show()
            } else startGitHubUpload(currentCopyText(), false)
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        uploadActions.addView(android.widget.Switch(this).apply {
            text = "自动上传"
            isChecked = sharedUploadPrefs.getBoolean("github_auto_upload", false)
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


        listOf(targetInput, connectInput, downloadInput, topNInput, thresholdInput, downloadCapInput).forEach { attachMainAutoSave(it) }
        thresholdInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val threshold = s?.toString()?.toDoubleOrNull() ?: return
                if (threshold >= 0.5 && resultLines.isNotEmpty()) {
                    val visible = filterResults(resultLines, threshold)
                    if (visible.isEmpty()) {
                        resultsView.textSize = 14f
                        resultsView.text = "没有达到显示标准的结果"
                    } else if (isOfficialMode) {
                        renderOfficialSpeedResults(visible.take(15))
                    } else {
                        resultsView.text = visible.joinToString("\n")
                    }
                    val displayedCount = if (isOfficialMode) minOf(visible.size, 15) else visible.size
                    status.text = "状态：结果筛选中；符合条件 ${visible.size} 条，当前显示 ${displayedCount} 条"
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        restoreMainPageSnapshot()
    }

    private fun restoreMainPageSnapshot() {
        val snapshot = mainPageSnapshot ?: return
        status.text = snapshot.statusText
        resultsView.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            snapshot.resultTextSize
        )
        resultsView.text = snapshot.resultText
        dataCenterSummaryView.text = snapshot.dataCenterSummaryText

        if (isOfficialMode) {
            // Restored result text retains its ClickableSpan, but a newly created
            // TextView must also restore the movement method that dispatches taps.
            resultsView.movementMethod = LinkMovementMethod.getInstance()
            resultsView.highlightColor = Color.TRANSPARENT
            renderOfficialDataCenterRows()
            if (snapshot.showingOfficialDataCenters) {
                showOfficialDataCenterList()
            } else {
                showOfficialSpeedResults()
            }
        }

        val scrollY = snapshot.scrollY
        root.post { (root.parent as? ScrollView)?.scrollTo(0, scrollY) }
        mainPageSnapshot = null
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
        urlsInput = edit("每行一个 HTTPS URL", savedNetworkURLs(), false)
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

    private fun ipv4ToLong(ip: String): Long {
        val octets = ip.split(".").map { it.toLong() }
        return (octets[0] shl 24) or (octets[1] shl 16) or
            (octets[2] shl 8) or octets[3]
    }

    private fun longToIPv4(value: Long): String =
        listOf(
            (value ushr 24) and 255,
            (value ushr 16) and 255,
            (value ushr 8) and 255,
            value and 255
        ).joinToString(".")

    private fun parseCloudflareCidr(line: String): IPv4CidrRange? {
        val cidr = line.trim()
        if (cidr.isEmpty()) return null
        val parts = cidr.split("/", limit = 2)
        if (parts.size != 2) return null
        val ip = validIPv4(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val hostBits = 32 - prefix
        val count = 1L shl hostBits
        if (count > Int.MAX_VALUE.toLong()) return null
        val mask = if (prefix == 0) 0L else (0xFFFF_FFFFL shl hostBits) and 0xFFFF_FFFFL
        val network = ipv4ToLong(ip) and mask
        return IPv4CidrRange("${longToIPv4(network)}/$prefix", network, count.toInt())
    }

    private fun refreshCloudflareIPv4Ranges(file: File): Int {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(cloudflareIPv4RangesUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IP-Speed-Android")
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP 状态码：$code")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val ranges = body.lineSequence().mapNotNull { parseCloudflareCidr(it) }
                .distinctBy { it.cidr }.toList()
            if (ranges.isEmpty()) throw IOException("Cloudflare 返回内容中没有有效 IPv4 网段")
            file.writeText(ranges.joinToString("\n") { it.cidr } + "\n", Charsets.UTF_8)
            return ranges.size
        } finally {
            connection?.disconnect()
        }
    }

    private fun loadCloudflareIPv4Ranges(file: File): List<IPv4CidrRange> {
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { parseCloudflareCidr(it) }
            .distinctBy { it.cidr }
    }

    private fun sampleCloudflareIPv4s(file: File, requestedCount: Int): List<String> {
        val ranges = loadCloudflareIPv4Ranges(file)
        if (ranges.isEmpty()) throw IOException("没有可用的 Cloudflare IPv4 网段缓存")
        val total = ranges.sumOf { it.count.toLong() }
        if (total < requestedCount.toLong()) {
            throw IOException("Cloudflare IPv4 网段总地址数不足 $requestedCount")
        }
        if (total > Int.MAX_VALUE.toLong()) {
            throw IOException("Cloudflare IPv4 网段总地址数超出当前随机抽样范围")
        }

        // 对所有网段中的地址位置进行无放回均匀抽样，抽样顺序也保持随机。
        val random = SecureRandom()
        val seenIndexes = HashSet<Int>(requestedCount * 2)
        val indexes = ArrayList<Int>(requestedCount)
        while (indexes.size < requestedCount) {
            val index = random.nextInt(total.toInt())
            if (seenIndexes.add(index)) indexes.add(index)
        }

        return indexes.map { index ->
            var remaining = index.toLong()
            var selected: String? = null
            for (range in ranges) {
                if (remaining < range.count.toLong()) {
                    selected = longToIPv4(range.network + remaining)
                    break
                }
                remaining -= range.count.toLong()
            }
            selected ?: throw IOException("Cloudflare IPv4 随机抽样映射失败")
        }.distinct().also {
            if (it.size != requestedCount) {
                throw IOException("随机抽样未能得到 $requestedCount 个不重复 IPv4")
            }
        }
    }

    private fun isTestCancelled(generation: Long): Boolean =
        generation != testGeneration || Thread.currentThread().isInterrupted

    private fun startTest(connectivityOnly: Boolean = false, selectedIPs: List<String>? = null) {
        if (!savedDataLoaded) {
            Toast.makeText(this, "本地 IP 数据仍在加载，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (testActive || process != null) {
            Toast.makeText(this, "测速正在运行", Toast.LENGTH_SHORT).show()
            return
        }
        val autoTargetSelected = currentTargetPreset() == "auto"
        val requiresISP = autoTargetSelected && !connectivityOnly
        val configuredTarget = if (autoTargetSelected) autoTargetLabel else targetInput.text.toString().trim()
        val capEnabled = downloadCapEnabledCheck.isChecked
        val capMiB = downloadCapInput.text.toString().trim().toDoubleOrNull()
        if (capEnabled && (capMiB == null || capMiB <= 0.0 || capMiB > 1_000_000.0)) {
            Toast.makeText(this, "下载上限必须是大于 0 且不超过 1000000 MiB 的数字", Toast.LENGTH_LONG).show()
            return
        }
        val downloadCapBytes = if (capEnabled) {
            (capMiB!! * 1024.0 * 1024.0).toLong()
        } else {
            50L * 1024L * 1024L
        }
        val connectN = connectInput.text.toString().trim().toIntOrNull()
        val downloadN = downloadInput.text.toString().trim().toIntOrNull()
        val topN = topNInput.text.toString().trim().toIntOrNull()
        val threshold = thresholdInput.text.toString().trim().toDoubleOrNull()
        if ((!autoTargetSelected && !configuredTarget.startsWith("https://")) ||
            connectN == null || connectN <= 0 ||
            downloadN == null || downloadN <= 0 || topN == null || topN <= 0 ||
            threshold == null || threshold < 0.1) {
            Toast.makeText(this, "请检查参数；最低显示速度不能低于 0.1 MiB/s", Toast.LENGTH_LONG).show()
            return
        }

        if (isOfficialMode) showOfficialSpeedResults()

        prefs.edit()
            .putString("target", configuredTarget)
            .putString("targetPreset", currentTargetPreset())
            .putString("download_cap_mib", downloadCapInput.text.toString())
            .putBoolean("download_cap_enabled", capEnabled)
            .putString("connectN", connectN.toString())
            .putString("downloadN", downloadN.toString())
            .putString("topN", topN.toString())
            .putString("threshold", threshold.toString())
            .apply()

        val localEnabled = prefs.getBoolean("localEnabled", true)
        val networkEnabled = prefs.getBoolean("networkEnabled", true)
        val clipboardText = if (!isOfficialMode && localEnabled) {
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() else ""
            } catch (_: Exception) {
                ""
            }
        } else ""
        val urls = savedNetworkURLs()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (!isOfficialMode && !localEnabled && !networkEnabled) {
            Toast.makeText(this, "请至少启用一个 IP 来源", Toast.LENGTH_LONG).show()
            return
        }
        if (!isOfficialMode && localEnabled && importedIPs.isEmpty() && !networkEnabled) {
            Toast.makeText(this, "本地来源已启用，但还没有导入 IP 文件；请启用网络来源或导入文件", Toast.LENGTH_LONG).show()
            return
        }

        val workingDir = if (isOfficialMode) File(filesDir, "official_work").apply { mkdirs() } else filesDir
        val binary = File(workingDir, "sss")
        val ipFile = File(workingDir, "ips.txt")
        try {
            if (!binary.exists()) assets.open("sss").use { input ->
                FileOutputStream(binary).use { output -> input.copyTo(output) }
            }
            binary.setExecutable(true, true)
        } catch (e: Exception) {
            Toast.makeText(this, "准备测速文件失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        if (!reserveTestTask(this)) {
            Toast.makeText(this, "另一个优选页面的任务尚未完成，请等待任务结束", Toast.LENGTH_LONG).show()
            return
        }
        testActive = true
        val generation = ++testGeneration

        // 按指定数据中心测速时，所有合格 IP 都参与；普通模式仍使用原来的低延迟数量参数。
        val effectiveTopN = topN
        // Android 原生网络栈读取 IP 来源，避免 Go 纯 Go DNS 解析器误用 ::1:53。
        resultLines = emptyList()
        resultsView.textSize = 14f
        resultsView.text = "正在准备测速…"
        status.text = "状态：正在启动"
        process = null
        val savedGeoRows = geoRowsByIP.toMap()
        val savedCountryCodes = countryCodesByIP.toMap()
        testFuture = executor.submit {
            val sourceLog = StringBuilder()
            val runGeoRows = linkedMapOf<String, String>()
            val runCountryCodes = linkedMapOf<String, String>()
            try {
                val resolvedTarget = if (requiresISP) {
                    runOnUiThread {
                        if (generation == testGeneration) {
                            status.text = "状态：正在查询运营商"
                            resultsView.text = "正在查询当前网络运营商…"
                        }
                    }
                    val isp = queryCurrentISPOnce()
                    val selectedTarget = if (isp == "中国移动") mobileTargetUrl else cloudflareTargetUrl
                    sourceLog.append("运营商识别：$isp；本次测速地址：$selectedTarget\n")
                    selectedTarget
                } else if (autoTargetSelected) {
                    defaultTargetUrl
                } else {
                    configuredTarget
                }
                // 每次真正开始测速只查询一次运营商；本次所有 IP 共用 resolvedTarget。
                val target = if (capEnabled) replaceTargetBytesParameter(resolvedTarget, downloadCapBytes) else resolvedTarget
                val args = mutableListOf(
                    binary.absolutePath, target, connectN.toString(), downloadN.toString(), effectiveTopN.toString(), "no", downloadCapBytes.toString()
                )
                if (connectivityOnly) args.add("connect-only")
                if (selectedIPs != null) args.add("speed-only")

                val mergedIPs = linkedSetOf<String>()
                if (selectedIPs != null) {
                    mergedIPs.addAll(selectedIPs.take(topN))
                    sourceLog.append("官方优选：使用当前数据中心已通过连通性测试的合格 IP，按低延迟 IP 数量设置选择 ${mergedIPs.size} 个进行测速；跳过重复连通性测试\n")
                } else if (isOfficialMode) {
                    val rangesFile = File(filesDir, "cloudflare_ipv4_ranges.txt")
                    var refreshed = false
                    var ranges = loadCloudflareIPv4Ranges(rangesFile)
                    if (ranges.isNotEmpty()) {
                        sourceLog.append("官方优选：读取本地 IPv4 网段缓存，共 ${ranges.size} 段，不重新下载\n")
                    } else {
                        sourceLog.append("官方优选：本地 IPv4 网段文件不存在或无有效内容，正在下载 Cloudflare 最新公布网段\n")
                        try {
                            val rangeCount = refreshCloudflareIPv4Ranges(rangesFile)
                            refreshed = true
                            sourceLog.append("已从 Cloudflare 官方下载并保存 IPv4 网段：$rangeCount 段\n")
                            ranges = loadCloudflareIPv4Ranges(rangesFile)
                        } catch (e: Exception) {
                            sourceLog.append("下载 Cloudflare 官方网段失败：${e.message ?: e.javaClass.simpleName}\n")
                        }
                    }
                    if (ranges.isEmpty()) {
                        throw IOException("Cloudflare 官方网段下载失败，且没有可用缓存")
                    }
                    val selectedIPs = sampleCloudflareIPv4s(rangesFile, officialRandomIPCount)
                    mergedIPs.addAll(selectedIPs)
                    sourceLog.append(
                        "官方优选：${if (refreshed) "使用刚下载的网段" else "使用已保存网段"}，完全随机抽取 ${mergedIPs.size} 个不重复 IPv4\n"
                    )
                } else {
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
                            if (isTestCancelled(generation)) return@submit
                            sourceLog.append("正在读取：${sourceURL}\n")
                            runOnUiThread {
                            if (generation != testGeneration) return@runOnUiThread
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
                                activeSourceConnection = connection
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
                                if (activeSourceConnection === connection) activeSourceConnection = null
                            }
                        }
                    } else {
                        sourceLog.append("网络来源：未启用\n")
                    }


                }

                if (isTestCancelled(generation)) return@submit
                if (mergedIPs.isEmpty()) {
                    runOnUiThread {
                            if (generation != testGeneration) return@runOnUiThread
                        process = null
                        resultsView.text = sourceLog.toString() + "读取 IP 来源失败：所有来源均未取得有效 IPv4"
                        status.text = "状态：IP 来源读取失败"
                    }
                    return@submit
                }

                if (isTestCancelled(generation)) return@submit
                // 写入前最后一次规范化去重；Go 核心读取的就是这份文件。
                val finalIPs = parseImported(mergedIPs.joinToString("\n"), false).first
                ipFile.writeText(finalIPs.joinToString("\n") + "\n")
                if (selectedIPs != null) {
                    val candidatesByIP = officialQualifiedByDataCenter.values.flatten().associateBy { it.ip }
                    val metadata = org.json.JSONObject()
                    finalIPs.forEach { ip ->
                        val candidate = candidatesByIP[ip] ?: return@forEach
                        metadata.put(ip, org.json.JSONObject()
                            .put("colo", candidate.colo)
                            .put("country", candidate.country)
                            .put("sourceLocation", candidate.sourceLocation)
                            .put("latencyMs", candidate.latencyMs)
                            .put("region", candidate.region)
                            .put("city", candidate.city))
                    }
                    File(workingDir, "ip_metadata.json").writeText(metadata.toString(), Charsets.UTF_8)
                }
                sourceLog.append("全部来源合并去重后：${finalIPs.size} 个唯一 IPv4\n")
                sourceLog.append("最终写入 ips.txt、交给测速核心：${finalIPs.size} 个 IP\n\n")
                runOnUiThread {
                            if (generation != testGeneration) return@runOnUiThread
                    status.text = "状态：即将测速，共 ${finalIPs.size} 个唯一 IP"
                    resultsView.text = sourceLog.toString()
                }
                val p = synchronized(testLock) {
                    if (isTestCancelled(generation)) return@submit
                    ProcessBuilder(args).directory(workingDir).redirectErrorStream(true).start().also { process = it }
                }
                runOnUiThread {
                    if (generation != testGeneration) return@runOnUiThread
                    status.text = "状态：测速进行中"
                }
                val all = StringBuilder()
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        all.append(line).append('\n')
                        runOnUiThread {
                            if (generation != testGeneration) return@runOnUiThread
                            status.text = "状态：测速进行中\n${line.takeLast(160)}"
                        }
                    }
                }
                val exitCode = p.waitFor()
                if (isTestCancelled(generation)) return@submit
                if (connectivityOnly) {
                    val candidates = all.toString().lineSequence()
                        .map { it.trim() }
                        .filter { it.startsWith("CONNECT|") }
                        .mapNotNull { line ->
                            val fields = line.split("|")
                            if (fields.size < 8) return@mapNotNull null
                            val ip = validIPv4(fields[1]) ?: return@mapNotNull null
                            OfficialCandidate(
                                ip = ip,
                                colo = fields[2].ifBlank { "UNK" },
                                country = fields[3].ifBlank { "XX" },
                                sourceLocation = fields[4].ifBlank { "XX" },
                                latencyMs = fields[5].toDoubleOrNull() ?: Double.MAX_VALUE,
                                region = fields[6],
                                city = fields[7]
                            )
                        }
                        .distinctBy { it.ip }
                        .toList()
                    runOnUiThread {
                            if (generation != testGeneration) return@runOnUiThread
                        process = null
                        if (exitCode != 0) {
                            showOfficialSpeedResults()
                            resultsView.text = ""
                            status.text = "状态：连通性测试异常退出（代码 $exitCode）"
                        } else {
                            officialQualifiedByDataCenter = candidates
                                .groupBy { it.colo }
                                .toSortedMap()
                                .mapValues { (_, rows) ->
                                    rows.sortedWith(compareBy<OfficialCandidate> { it.latencyMs }.thenBy { it.ip })
                                }
                            renderOfficialDataCenterRows()
                            showOfficialDataCenterList()
                            val qualifiedCount = candidates.size
                            status.text = "状态：连通性测试完成；合格 IP $qualifiedCount 个"
                            dataCenterSummaryView.text = "连通性测试完成：${officialQualifiedByDataCenter.size} 个数据中心，$qualifiedCount 个合格 IP。点击对应数据中心的“开始测速”进行单独测速。"
                            runAutomaticOfficialSpeedTestIfEnabled()
                        }
                    }
                    return@submit
                }
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
                    if (isOfficialMode) {
                        "$ip:443#官方优选-${speed}MB/s"
                    } else {
                        "$ip:443#$colo-$sourceLocation-${speed}MB/s"
                    }
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
                    listOf(colo, country, latency.toString(), region, city, ip)
                }.filter { it[0] != "UNK" }
                val dcSummary = dcStats.groupBy { it[0] }.entries.sortedBy { it.key }.map { (colo, rows) ->
                    val first = rows.first()
                    val validLatencies = rows.mapNotNull { it[2].toLongOrNull() }.filter { it != Long.MAX_VALUE }
                    val minLatency = validLatencies.minOrNull()?.let { "${it} ms" } ?: "未知"
                    val location = listOf(first[3], first[4]).filter { it.isNotBlank() }.distinct().joinToString(" / ")
                    val sourceIPs = rows.map { it[5] }.distinct().joinToString("、")
                    "$colo-${first[1]}（$sourceIPs）：${rows.size} 个 IP；最低延迟 $minLatency" +
                        if (location.isNotBlank()) "；$location" else ""
                }
                resultLines = parsed
                val outputText = sourceLog.toString() + all.toString().trim()
                runOnUiThread {
                            if (generation != testGeneration) return@runOnUiThread
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
                        if (visible.isEmpty()) {
                            resultsView.textSize = 14f
                            resultsView.text = "没有达到显示标准的结果"
                        } else {
                            renderSpeedResults(visible.take(15))
                        }
                        // Keep all filtered results for copy, saved history, and upload;
                        // only the on-screen list is capped at 15 rows.
                        val snapshot = visible.joinToString("\n")
                        val saved = try { prefs.edit().putString("last_completed_result", snapshot).commit() } catch (_: Exception) { false }
                        if (!saved) Toast.makeText(this, "最近一次测速结果保存失败", Toast.LENGTH_LONG).show()
                        status.text = "状态：完成；结果 ${visible.size} 条"
                        if (sharedUploadPrefs.getBoolean("github_auto_upload", false) && snapshot.isNotBlank()) {
                            startGitHubUpload(snapshot, true)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                            if (generation != testGeneration) return@runOnUiThread
                    process = null
                    val detail = e.message ?: e.javaClass.simpleName
                    status.text = if (requiresISP) "状态：运营商识别失败，已停止测速" else "状态：启动失败"
                    resultsView.text = if (requiresISP) "运营商识别失败，无法获取运营商信息，已停止测速。\n$detail" else e.toString()
                }
            } finally {
                if (generation == testGeneration) {
                    testActive = false
                    process = null
                    testFuture = null
                }
                releaseTestTask(this@MainActivity)
            }
        }
    }

    private fun renderOfficialDataCenterRows() {
        if (!isOfficialMode || !::dataCenterRowsContainer.isInitialized) return
        dataCenterRowsContainer.removeAllViews()
        if (officialQualifiedByDataCenter.isEmpty()) {
            dataCenterRowsContainer.addView(label("暂无合格数据中心", 13f))
            return
        }
        officialQualifiedByDataCenter.toList()
            .sortedWith(
                compareBy<Pair<String, List<OfficialCandidate>>> {
                    it.second.minOfOrNull { candidate -> candidate.latencyMs } ?: Double.MAX_VALUE
                }.thenBy { it.first }
            )
            .forEach { (colo, candidates) ->
            val first = candidates.firstOrNull()
            val country = first?.country ?: "XX"
            val minLatency = candidates.minOfOrNull { it.latencyMs } ?: Double.MAX_VALUE
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(this).apply {
                text = "$colo-$country"
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
            }, LinearLayout.LayoutParams(0, -2, 1.05f))
            row.addView(TextView(this).apply {
                text = "${candidates.size} 个"
                textSize = 12f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 0.72f))
            row.addView(TextView(this).apply {
                text = if (minLatency == Double.MAX_VALUE) "未知" else String.format(Locale.getDefault(), "%.2f ms", minLatency)
                textSize = 12f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1.0f))
            row.addView(button("开始测速") {
                startOfficialDataCenterSpeedTest(colo)
            }.apply {
                textSize = 11f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(3), 0, dp(3), 0)
            }, LinearLayout.LayoutParams(dp(88), -2))
            dataCenterRowsContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
            dataCenterRowsContainer.addView(View(this).apply {
                setBackgroundColor(0xFFD9DDE2.toInt())
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun showOfficialDataCenterList() {
        if (!isOfficialMode) return
        val scroll = officialDataCenterScroll ?: return
        resultsView.visibility = View.GONE
        scroll.visibility = View.VISIBLE
    }

    private fun showOfficialSpeedResults() {
        if (!isOfficialMode) return
        resultsView.visibility = View.VISIBLE
        officialDataCenterScroll?.visibility = View.GONE
    }

    private fun startOfficialDataCenterSpeedTest(colo: String) {
        val candidates = officialQualifiedByDataCenter[colo].orEmpty()
        if (candidates.isEmpty()) {
            Toast.makeText(this, "该数据中心没有合格 IP", Toast.LENGTH_SHORT).show()
            return
        }
        showOfficialSpeedResults()
        startTest(connectivityOnly = false, selectedIPs = candidates.map { it.ip })
    }

    private fun runAutomaticOfficialSpeedTestIfEnabled() {
        if (!isOfficialMode || !::officialAutoSpeedCheck.isInitialized || !officialAutoSpeedCheck.isChecked) return

        val requested = if (::officialDataCenterInput.isInitialized) {
            officialDataCenterInput.text.toString().trim()
        } else {
            ""
        }
        val targetColo = if (requested.isBlank()) {
            officialQualifiedByDataCenter.entries.minWithOrNull(
                compareBy<Map.Entry<String, List<OfficialCandidate>>> {
                    it.value.minOfOrNull { candidate -> candidate.latencyMs } ?: Double.MAX_VALUE
                }.thenBy { it.key }
            )?.key
        } else {
            officialQualifiedByDataCenter.keys.firstOrNull { it.equals(requested, ignoreCase = true) }
        }

        if (targetColo == null) {
            val message = if (requested.isBlank()) {
                "暂无合格数据中心，本次不测速"
            } else {
                "未找到数据中心：$requested，本次不测速"
            }
            status.text = "状态：$message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        startOfficialDataCenterSpeedTest(targetColo)
    }

    private fun resultSpeed(line: String): Double {
        val officialFormatted = Regex("#官方优选-([0-9]+(?:\\.[0-9]+)?)MB/s$").find(line)
        if (officialFormatted != null) return officialFormatted.groupValues[1].toDoubleOrNull() ?: 0.0
        val formatted = Regex("#[A-Z]{3}-[A-Z]{2}-([0-9]+(?:\\.[0-9]+)?)MB/s$").find(line)
        if (formatted != null) return formatted.groupValues[1].toDoubleOrNull() ?: 0.0
        return line.substringAfter("#").substringBefore("M").toDoubleOrNull() ?: 0.0
    }

    private fun renderOfficialSpeedResults(lines: List<String>) {
        renderSpeedResults(lines)
    }

    private fun renderSpeedResults(lines: List<String>) {
        resultsView.textSize = 16f
        val content = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val ip = line.substringBefore(":443#")
            val lineStart = content.length
            content.append(line)
            val ipEnd = lineStart + ip.length
            if (ip.isNotBlank() && ipEnd <= content.length) {
                content.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IP", ip))
                        Toast.makeText(this@MainActivity, "已复制 IP：$ip", Toast.LENGTH_SHORT).show()
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = Color.rgb(33, 150, 243)
                        ds.isUnderlineText = true
                    }
                }, lineStart, ipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (index < lines.lastIndex) content.append("\n")
        }
        resultsView.text = content
        resultsView.movementMethod = LinkMovementMethod.getInstance()
        resultsView.highlightColor = Color.TRANSPARENT
    }

    private fun filterResults(lines: List<String>, threshold: Double): List<String> =
        lines.filter { resultSpeed(it) >= threshold }
            .sortedByDescending { resultSpeed(it) }

    private fun stopTest() {
        val hadTask = testActive || process != null || testFuture?.isDone == false
        synchronized(testLock) {
            testGeneration += 1
            testFuture?.cancel(true)
            testFuture = null
            activeSourceConnection?.disconnect()
            activeSourceConnection = null
            process?.destroyForcibly()
            process = null
            testActive = false
            releaseTestTask(this)
        }
        status.text = if (hadTask) "状态：已停止全部任务" else "状态：当前没有运行中的测速任务"
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
            else -> moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        process?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
