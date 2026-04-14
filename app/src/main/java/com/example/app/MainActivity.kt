package com.example.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ファイルデータクラス
data class FileItem(var name: String, var content: String, var active: Boolean = false)

class MainActivity : AppCompatActivity() {

    // ── 状態変数 ──
    private val files = mutableListOf(
        FileItem("index.html", "<!DOCTYPE html>\n<html>\n<body>\n  <h1>Hello Cloud Build</h1>\n</body>\n</html>", true)
    )
    private var isTranslating = false
    private var ghToken = ""
    private var selectedRepo = ""
    private var appNameVal = "My App"
    private var appIdVal = "com.example.app"
    private var branchVal = "main"
    private var activeProvider = "claude"
    private val apiKeys = mutableMapOf<String, String>()
    private val modelSelections = mutableMapOf<String, String>()

    // SharedPreferencesキー
    private val PREFS = "CloudIDEPrefs"

    // AIモデル定義
    private val AI_MODELS = mapOf(
        "claude" to listOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5"),
        "openai" to listOf("gpt-4o", "gpt-4o-mini", "o1", "o1-mini"),
        "gemini" to listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"),
        "xai"    to listOf("grok-2-latest", "grok-2-mini", "grok-beta")
    )
    private val AI_DEFAULT_MODELS = mapOf(
        "claude" to "claude-sonnet-4-6",
        "openai" to "gpt-4o",
        "gemini" to "gemini-2.5-flash",
        "xai"    to "grok-2-latest"
    )
    private val AI_NAMES = mapOf(
        "claude" to "Claude (Anthropic)",
        "openai" to "GPT (OpenAI)",
        "gemini" to "Gemini (Google)",
        "xai"    to "Grok (xAI)"
    )

    // ── カラー定数 ──
    private val bgMain      = Color.parseColor("#0d1117")
    private val bgSurface   = Color.parseColor("#161b22")
    private val bgElevated  = Color.parseColor("#21262d")
    private val textMain    = Color.parseColor("#c9d1d9")
    private val textMuted   = Color.parseColor("#6e7681")
    private val textBright  = Color.parseColor("#f0f6fc")
    private val colorPrimary = Color.parseColor("#58a6ff")
    private val colorBorder = Color.parseColor("#30363d")
    private val colorSuccess = Color.parseColor("#3fb950")
    private val colorError  = Color.parseColor("#f85149")
    private val colorWarning = Color.parseColor("#d29922")

    // ── UI参照 ──
    private lateinit var tabBar: LinearLayout
    private lateinit var editorArea: EditText
    private lateinit var logConsole: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var activeRepoDisplay: TextView
    private lateinit var deployBtn: Button
    private lateinit var pipelinePanel: LinearLayout
    private lateinit var pipelineElapsed: TextView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var consoleHeader: LinearLayout
    private var consoleCollapsed = true

    // パイプラインステップUI
    private val stepViews = mutableMapOf<String, TextView>()
    private val stepLabels = mutableMapOf<String, TextView>()

    // パイプラインタイマー
    private var pipelineJob: Job? = null
    private var pipelineStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPrefs()

        val root = buildUI()
        setContentView(root)
        updateTabBar()
    }

    // ════════════════════════════════════════════
    // ── UI構築 ──
    // ════════════════════════════════════════════
    private fun buildUI(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ヘッダー
        root.addView(buildHeader())

        // タブバー
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(bgSurface)
        }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgSurface)
        }
        tabScroll.addView(tabBar)
        root.addView(tabScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ))

        // エディタエリア
        val editorScroll = ScrollView(this).apply {
            setBackgroundColor(bgMain)
        }
        editorArea = EditText(this).apply {
            setBackgroundColor(bgMain)
            setTextColor(textMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) saveCurrentFileContent()
            }
        }
        files.firstOrNull { it.active }?.let { editorArea.setText(it.content) }
        editorScroll.addView(editorArea, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(editorScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // パイプラインパネル
        pipelinePanel = buildPipelinePanel()
        pipelinePanel.visibility = View.GONE
        root.addView(pipelinePanel)

        // コンソール
        bottomPanel = buildConsolePanel()
        root.addView(bottomPanel)

        return root
    }

    // ── ヘッダー ──
    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgSurface)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val logo = TextView(this).apply {
            text = "⚡ IDE"
            setTextColor(colorPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(8), 0)
        }
        header.addView(logo)

        // ZIPエクスポートボタン
        header.addView(buildSmallButton("📦") { exportZip() })
        header.addView(buildSmallButton("📥") { showImportDialog() })
        header.addView(buildSmallButton("🤖") { translateToKotlin() })

        // リポジトリ表示
        activeRepoDisplay = TextView(this).apply {
            text = if (selectedRepo.isNotEmpty()) selectedRepo.substringAfter('/') else "未接続"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(bgElevated)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val spacer = View(this)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(activeRepoDisplay)

        // デプロイボタン
        deployBtn = Button(this).apply {
            text = "🚀 デプロイ"
            setTextColor(textBright)
            setBackgroundColor(colorPrimary)
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { openDeployDialog() }
        }
        header.addView(deployBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
        ).apply { marginStart = dp(6) })

        // 設定ボタン
        header.addView(buildSmallButton("⚙") { openSettings() })

        return header
    }

    private fun buildSmallButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(textMuted)
            setBackgroundColor(bgElevated)
            textSize = 12f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(2) }
            setOnClickListener { action() }
        }
    }

    // ── タブバー更新 ──
    private fun updateTabBar() {
        tabBar.removeAllViews()
        files.forEachIndexed { idx, file ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (file.active) bgMain else bgSurface)
                setPadding(dp(10), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
                )
            }
            val nameView = TextView(this).apply {
                text = file.name
                setTextColor(if (file.active) textBright else textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setOnClickListener { switchTab(idx) }
                setOnLongClickListener { renameFileDialog(idx); true }
            }
            val closeView = TextView(this).apply {
                text = "✕"
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(6), 0, dp(2), 0)
                setOnClickListener { removeFile(idx) }
            }
            tab.addView(nameView)
            if (file.active) tab.addView(closeView)
            tabBar.addView(tab)
        }
        // 新規ボタン
        val addBtn = TextView(this).apply {
            text = "+ 新規"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
            )
            setOnClickListener { addNewFileDialog() }
        }
        tabBar.addView(addBtn)
    }

    // ── パイプラインUI ──
    private fun buildPipelinePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSurface)
        }
        // ヘッダー行
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val title = TextView(this).apply {
            text = "Pipeline"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            setTextColor(textMuted)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { closePipeline() }
        }
        val sp = View(this)
        header.addView(title)
        header.addView(sp, LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(closeBtn)
        panel.addView(header)

        // ステップトラック
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val steps = listOf(
            "upload" to "📦",
            "commit" to "📝",
            "trigger" to "⚡",
            "build" to "🔨",
            "done" to "📥"
        )
        steps.forEachIndexed { i, (id, icon) ->
            val stepCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val iconView = TextView(this).apply {
                text = icon
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                setBackgroundColor(bgElevated)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }
            val labelView = TextView(this).apply {
                text = id.replaceFirstChar { it.uppercase() }
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                gravity = Gravity.CENTER
            }
            stepCol.addView(iconView)
            stepCol.addView(labelView)
            stepViews[id] = iconView
            stepLabels[id] = labelView
            track.addView(stepCol)
            if (i < steps.size - 1) {
                val connector = View(this).apply {
                    setBackgroundColor(colorBorder)
                    layoutParams = LinearLayout.LayoutParams(0, dp(2), 1f)
                }
                track.addView(connector)
            }
        }
        panel.addView(track)

        pipelineElapsed = TextView(this).apply {
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.END
            setPadding(0, 0, dp(16), dp(6))
        }
        panel.addView(pipelineElapsed)
        return panel
    }

    // ── コンソールUI ──
    private fun buildConsolePanel(): LinearLayout {
        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#010409"))
        }

        consoleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgSurface)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            minimumHeight = dp(34)
            setOnClickListener { toggleConsole() }
        }
        val consoleLabel = TextView(this).apply {
            text = "▼ Console"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        val clearBtn = Button(this).apply {
            text = "クリア"
            setTextColor(textMuted)
            setBackgroundColor(bgElevated)
            textSize = 10f
            setOnClickListener { clearConsole() }
        }
        val sp2 = View(this)
        consoleHeader.addView(consoleLabel)
        consoleHeader.addView(sp2, LinearLayout.LayoutParams(0, 1, 1f))
        consoleHeader.addView(clearBtn)
        bottomPanel.addView(consoleHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(34)
        ))

        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#010409"))
        }
        logConsole = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        logScroll.addView(logConsole)
        bottomPanel.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(120)
        ))

        // 初期は折りたたみ
        logScroll.visibility = View.GONE
        consoleCollapsed = true

        return bottomPanel
    }

    private fun toggleConsole() {
        consoleCollapsed = !consoleCollapsed
        logScroll.visibility = if (consoleCollapsed) View.GONE else View.VISIBLE
    }

    private fun clearConsole() {
        logConsole.removeAllViews()
    }

    // ════════════════════════════════════════════
    // ── ロギング ──
    // ════════════════════════════════════════════
    private fun log(msg: String, type: String = "") {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val color = when (type) {
            "suc" -> colorSuccess
            "err" -> colorError
            else -> textMuted
        }
        runOnUiThread {
            val tv = TextView(this).apply {
                text = "[$time] $msg"
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
            }
            logConsole.addView(tv)
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            // ログが来たら自動展開
            if (consoleCollapsed) {
                consoleCollapsed = false
                logScroll.visibility = View.VISIBLE
            }
        }
    }

    // ════════════════════════════════════════════
    // ── タブ操作 ──
    // ════════════════════════════════════════════
    private fun switchTab(idx: Int) {
        saveCurrentFileContent()
        files.forEachIndexed { i, f -> f.active = (i == idx) }
        editorArea.setText(files[idx].content)
        updateTabBar()
    }

    private fun saveCurrentFileContent() {
        val activeIdx = files.indexOfFirst { it.active }
        if (activeIdx >= 0) {
            files[activeIdx].content = editorArea.text.toString()
        }
    }

    private fun addNewFileDialog() {
        val input = EditText(this).apply {
            hint = "ファイル名"
            setTextColor(textMain)
            setHintTextColor(textMuted)
            setBackgroundColor(bgElevated)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("新規ファイル")
            .setView(input)
            .setPositiveButton("作成") { _, _ ->
                val name = sanitizeFileName(input.text.toString().trim()) ?: return@setPositiveButton
                if (files.any { it.name == name }) {
                    log("「$name」は既に存在します", "err")
                    return@setPositiveButton
                }
                saveCurrentFileContent()
                files.forEach { it.active = false }
                files.add(FileItem(name, "", true))
                editorArea.setText("")
                updateTabBar()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun renameFileDialog(idx: Int) {
        val input = EditText(this).apply {
            setText(files[idx].name)
            setTextColor(textMain)
            setBackgroundColor(bgElevated)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("ファイル名を変更")
            .setView(input)
            .setPositiveButton("変更") { _, _ ->
                val newName = sanitizeFileName(input.text.toString().trim()) ?: return@setPositiveButton
                if (newName == files[idx].name) return@setPositiveButton
                if (files.any { it.name == newName }) {
                    log("「$newName」は既に存在します", "err")
                    return@setPositiveButton
                }
                files[idx].name = newName
                updateTabBar()
                log("リネーム → $newName", "suc")
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun removeFile(idx: Int) {
        if (files.size == 1) { log("最後のファイルは削除できません", "err"); return }
        saveCurrentFileContent()
        val wasActive = files[idx].active
        files.removeAt(idx)
        if (wasActive) {
            files[maxOf(0, idx - 1)].active = true
            editorArea.setText(files[maxOf(0, idx - 1)].content)
        }
        updateTabBar()
    }

    private fun sanitizeFileName(name: String): String? {
        var clean = name.replace("/", "_").replace("\\", "_").replace("..", "_")
        if (clean.isEmpty() || clean == "." || clean == "..") {
            log("無効なファイル名です", "err")
            return null
        }
        return clean
    }

    // ════════════════════════════════════════════
    // ── パイプライン ──
    // ════════════════════════════════════════════
    private fun openPipeline() {
        runOnUiThread {
            pipelinePanel.visibility = View.VISIBLE
            pipelineStart = System.currentTimeMillis()
            pipelineJob?.cancel()
            pipelineJob = lifecycleScope.launch {
                while (isActive) {
                    val elapsed = (System.currentTimeMillis() - pipelineStart) / 1000
                    val min = elapsed / 60
                    val sec = elapsed % 60
                    pipelineElapsed.text = "$min:${sec.toString().padStart(2, '0')}"
                    delay(1000)
                }
            }
        }
    }

    private fun closePipeline() {
        pipelinePanel.visibility = View.GONE
        pipelineJob?.cancel()
    }

    private fun setStep(id: String, state: String, detail: String = "") {
        runOnUiThread {
            val iconView = stepViews[id] ?: return@runOnUiThread
            val labelView = stepLabels[id] ?: return@runOnUiThread
            when (state) {
                "done" -> {
                    iconView.setBackgroundColor(Color.parseColor("#1a3a2a"))
                    iconView.setTextColor(colorSuccess)
                    labelView.setTextColor(colorSuccess)
                }
                "active" -> {
                    iconView.setBackgroundColor(Color.parseColor("#1f3a5f"))
                    iconView.setTextColor(colorPrimary)
                    labelView.setTextColor(colorPrimary)
                }
                "fail" -> {
                    iconView.setBackgroundColor(Color.parseColor("#3d1a1a"))
                    iconView.setTextColor(colorError)
                    labelView.setTextColor(colorError)
                }
                else -> {
                    iconView.setBackgroundColor(bgElevated)
                    iconView.setTextColor(textMuted)
                    labelView.setTextColor(textMuted)
                }
            }
        }
    }

    // ════════════════════════════════════════════
    // ── デプロイダイアログ ──
    // ════════════════════════════════════════════
    private fun openDeployDialog() {
        if (selectedRepo.isEmpty()) {
            Toast.makeText(this, "設定画面でリポジトリを選択してください", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(bgSurface)
        }

        fun field(labelText: String, value: String, hint: String = ""): EditText {
            val label = TextView(this).apply {
                text = labelText
                setTextColor(textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, dp(8), 0, dp(4))
            }
            val input = EditText(this).apply {
                setText(value)
                setTextColor(textMain)
                setHintTextColor(textMuted)
                setBackgroundColor(bgElevated)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT
                if (hint.isNotEmpty()) this.hint = hint
            }
            dialogLayout.addView(label)
            dialogLayout.addView(input)
            return input
        }

        val nameInput = field("アプリ名", appNameVal, "My App")
        val idInput = field("パッケージID", appIdVal, "com.example.app")
        val branchInput = field("ブランチ", branchVal, "main")

        val repoInfo = TextView(this).apply {
            text = "リポジトリ: $selectedRepo"
            setTextColor(textBright)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        }
        dialogLayout.addView(repoInfo)

        AlertDialog.Builder(this)
            .setTitle("🚀 デプロイ設定")
            .setView(dialogLayout)
            .setPositiveButton("デプロイ実行") { _, _ ->
                appNameVal = nameInput.text.toString().ifEmpty { "My App" }
                appIdVal = idInput.text.toString().ifEmpty { "com.example.app" }
                branchVal = branchInput.text.toString().trim().ifEmpty { "main" }
                val err = validateAppId(appIdVal)
                if (err != null) { log(err, "err"); Toast.makeText(this, err, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                savePrefs()
                executeDeploy()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ── バリデーション ──
    private fun validateAppId(appId: String): String? {
        val regex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+\$")
        if (!regex.matches(appId)) return "パッケージIDが不正です。例: com.example.app"
        val reserved = setOf("abstract","assert","boolean","break","byte","case","catch","char","class","const",
            "continue","default","do","double","else","enum","extends","final","finally","float","for","goto",
            "if","implements","import","instanceof","int","interface","long","native","new","package","private",
            "protected","public","return","short","static","strictfp","super","switch","synchronized","this",
            "throw","throws","transient","try","void","volatile","while")
        for (seg in appId.split(".")) {
            if (reserved.contains(seg)) return "予約語「$seg」は使えません"
        }
        return null
    }

    // ────────────────────────────────────────────
    // ── デプロイ実行 ──
    // ────────────────────────────────────────────
    private fun executeDeploy() {
        saveCurrentFileContent()
        openPipeline()
        deployBtn.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("$selectedRepo ($branchVal) へデプロイ開始")

                // Step1: www.zip 作成 & アップロード (簡易: index.htmlのみBase64)
                setStep("upload", "active", "準備中...")
                val nonKtContent = files.filter { !it.name.endsWith(".kt") }
                    .joinToString("\n---\n") { "// ${it.name}\n${it.content}" }
                val zipBase64 = android.util.Base64.encodeToString(
                    nonKtContent.toByteArray(), android.util.Base64.NO_WRAP
                )
                val existingSha = try {
                    val r = ghApiGet("/contents/www.zip")
                    r?.optString("sha")
                } catch (e: Exception) { null }

                val uploadBody = JSONObject().apply {
                    put("message", "chore: update web assets")
                    put("content", zipBase64)
                    if (!existingSha.isNullOrEmpty()) put("sha", existingSha)
                }
                ghApiPut("/contents/www.zip", uploadBody)
                setStep("upload", "done", "完了")
                log("www.zip アップロード完了")

                // Step2: .ktファイルコミット
                val ktFiles = files.filter {