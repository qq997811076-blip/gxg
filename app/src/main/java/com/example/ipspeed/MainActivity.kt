package com.example.ipspeed

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var importedIPs: List<String> = emptyList()
    private var resultLines: List<String> = emptyList()
    private var sourcePage = false

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var resultsView: TextView
    private lateinit var targetInput: EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importedIPs = prefs.getString("imported_ips", "")!!
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        showMain()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeRoot(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val scroll = ScrollView(this)
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

    private fun showMain() {
        sourcePage = false
        makeRoot()
        root.addView(label("IP 测速", 24f))
        root.addView(label("保留原 Go 测速核心；界面通过独立适配层调用。", 14f))

        targetInput = edit("HTTPS 测速地址", prefs.getString("target", "https://cesu.dxgees.ggff.net")!!)
        connectInput = edit("连通性并发数", prefs.getString("connectN", "300")!!)
        downloadInput = edit("下载测速并发数", prefs.getString("downloadN", "100")!!)
        topNInput = edit("参与下载测速的低延迟 IP 数量", prefs.getString("topN", "20")!!)
        thresholdInput = edit("最低显示速度（MiB/s）", prefs.getString("threshold", "0.5")!!)
        addField("测速地址（HTTPS）", targetInput)
        addField("连通性检测并发数", connectInput)
        addField("下载测速并发数", downloadInput)
        addField("延迟最低筛选数量", topNInput)
        addField("最低显示速度（默认 0.5 MiB/s）", thresholdInput)

        root.addView(button("IP 来源管理") { showSources() })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("开始测速") { startTest() }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("停止测速") { stopTest() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        root.addView(button("复制当前显示结果") { copyResults() })
        status = label("状态：待开始", 14f)
        root.addView(status)
        root.addView(label("合格结果（按速度从低到高）", 18f))
        resultsView = label("暂无结果")
        resultsView.textSize = 15f
        resultsView.setPadding(dp(10), dp(10), dp(10), dp(10))
        resultsView.setBackgroundColor(0xFFEFEFEF.toInt())
        root.addView(resultsView, LinearLayout.LayoutParams(-1, dp(280)))
    }

    private fun showSources() {
        sourcePage = true
        makeRoot()
        root.addView(label("IP 来源管理", 24f))
        root.addView(label("TXT：忽略行首空白，只保留每行开头的 IPv4；尾部内容忽略。CSV：从字段中提取 IPv4。IPv6 不支持。", 14f))
        root.addView(button("选择 TXT / CSV 文件") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/csv", "application/csv", "application/vnd.ms-excel"))
            }
            startActivityForResult(intent, 701)
        })
        sourceStats = label("有效唯一 IPv4：${importedIPs.size}；重复数量：—", 14f)
        root.addView(sourceStats)
        root.addView(button("清除已导入的本地 IP") {
            importedIPs = emptyList()
            prefs.edit().putString("imported_ips", "").apply()
            sourceStats.text = "有效唯一 IPv4：0；重复数量：0"
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
        })

        localCheck = CheckBox(this).apply {
            text = "启用已选择的本地文件 IP"
            isChecked = prefs.getBoolean("localEnabled", true)
        }
        networkCheck = CheckBox(this).apply {
            text = "启用网络 URL 来源"
            isChecked = prefs.getBoolean("networkEnabled", true)
        }
        root.addView(localCheck)
        root.addView(networkCheck)
        root.addView(label("网络 URL（每行一个，可直接编辑、添加或删除）"))
        urlsInput = edit("每行一个 HTTPS URL", prefs.getString("urls", defaults.joinToString("\n"))!!, false)
        root.addView(urlsInput, LinearLayout.LayoutParams(-1, dp(160)))
        root.addView(button("保存网络 URL") {
            prefs.edit().putString("urls", urlsInput.text.toString()).apply()
            Toast.makeText(this, "URL 已保存", Toast.LENGTH_SHORT).show()
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701 || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val name = queryName(uri).lowercase(Locale.ROOT)
            val isCsv = name.endsWith(".csv") || content.lineSequence().firstOrNull()?.contains(",") == true
            val parsed = parseImported(content, isCsv)
            importedIPs = parsed.first
            prefs.edit().putString("imported_ips", importedIPs.joinToString("\n")).apply()
            sourceStats.text = "有效唯一 IPv4：${parsed.first.size}；重复数量：${parsed.second}"
            Toast.makeText(this, "导入完成", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败：${e.message}", Toast.LENGTH_LONG).show()
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

    private fun parseImported(content: String, csv: Boolean): Pair<List<String>, Int> {
        val found = mutableListOf<String>()
        val regex = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
        for (line in content.lineSequence()) {
            if (csv) {
                for (match in regex.findAll(line)) {
                    val ip = validIPv4(match.value)
                    if (ip != null) found.add(ip)
                }
            } else {
                val trimmed = line.trimStart()
                val match = Regex("^([0-9]{1,3}(?:\\.[0-9]{1,3}){3})").find(trimmed) ?: continue
                val ip = validIPv4(match.groupValues[1])
                if (ip != null) found.add(ip)
            }
        }
        val unique = linkedSetOf<String>()
        var duplicates = 0
        for (ip in found) {
            if (!unique.add(ip)) duplicates++
        }
        return unique.toList() to duplicates
    }

    private fun startTest() {
        if (process != null) {
            Toast.makeText(this, "测速正在运行", Toast.LENGTH_SHORT).show()
            return
        }
        val target = targetInput.text.toString().trim()
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
            .putString("connectN", connectN.toString())
            .putString("downloadN", downloadN.toString())
            .putString("topN", topN.toString())
            .putString("threshold", threshold.toString())
            .apply()

        val localEnabled = prefs.getBoolean("localEnabled", true)
        val networkEnabled = prefs.getBoolean("networkEnabled", true)
        val urls = prefs.getString("urls", defaults.joinToString("\n"))!!
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (!localEnabled && !networkEnabled) {
            Toast.makeText(this, "请至少启用一个 IP 来源", Toast.LENGTH_LONG).show()
            return
        }
        if (localEnabled && importedIPs.isEmpty()) {
            Toast.makeText(this, "本地来源已启用，但还没有导入 IP 文件", Toast.LENGTH_LONG).show()
            return
        }

        val binary = File(filesDir, "sss")
        try {
            if (!binary.exists()) assets.open("sss").use { input ->
                FileOutputStream(binary).use { output -> input.copyTo(output) }
            }
            binary.setExecutable(true, true)
            val ipFile = File(filesDir, "ips.txt")
            if (localEnabled) ipFile.writeText(importedIPs.joinToString("\n"))
            else ipFile.delete()
        } catch (e: Exception) {
            Toast.makeText(this, "准备测速文件失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val args = mutableListOf(
            binary.absolutePath, target, connectN.toString(), downloadN.toString(), topN.toString(), "no"
        )
        if (networkEnabled) args.addAll(urls)
        resultLines = emptyList()
        resultsView.text = "正在准备测速…"
        status.text = "状态：正在启动"
        process = null
        executor.execute {
            try {
                val p = ProcessBuilder(args).directory(filesDir).redirectErrorStream(true).start()
                process = p
                runOnUiThread { status.text = "状态：测速进行中" }
                val all = StringBuilder()
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        all.append(line).append('\n')
                        runOnUiThread {
                            status.text = "状态：测速进行中\n${line.takeLast(160)}"
                        }
                    }
                }
                val exitCode = p.waitFor()
                val parsed = all.toString().lineSequence()
                    .map { it.trim() }
                    .filter { Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}#[0-9]+(?:\\.[0-9]+)?M$").matches(it) }
                    .toList()
                resultLines = parsed
                runOnUiThread {
                    process = null
                    val visible = filterResults(parsed, threshold)
                    resultsView.text = if (visible.isEmpty()) "没有达到显示标准的结果" else visible.joinToString("\n\n")
                    status.text = "状态：${if (exitCode == 0) "完成" else "结束（代码 $exitCode）"}；结果 ${visible.size} 条"
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

    private fun filterResults(lines: List<String>, threshold: Double): List<String> =
        lines.filter { line ->
            val speed = line.substringAfter("#").removeSuffix("M").toDoubleOrNull() ?: 0.0
            speed >= threshold
        }

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
        val threshold = thresholdInput.text.toString().toDoubleOrNull() ?: 0.5
        val text = filterResults(resultLines, threshold).joinToString("\n")
        if (text.isBlank()) {
            Toast.makeText(this, "没有可复制的结果", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("测速结果", text))
        Toast.makeText(this, "已复制 ${text.lines().size} 条结果", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        process?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
