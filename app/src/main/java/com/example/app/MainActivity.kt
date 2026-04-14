package com.example.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.HorizontalScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    // dp変換ヘルパー
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    // 色定数
    private val bgMain = Color.parseColor("#0d1117")
    private val bgSurface = Color.parseColor("#161b22")
    private val bgElevated = Color.parseColor("#21262d")
    private val textMain = Color.parseColor("#c9d1d9")
    private val textMuted = Color.parseColor("#6e7681")
    private val textBright = Color.parseColor("#f0f6fc")
    private val colorPrimary = Color.parseColor("#58a6ff")
    private val colorBorder = Color.parseColor("#30363d")
    private val colorSuccess = Color.parseColor("#3fb950")
    private val colorError = Color.parseColor("#f85149")
    private val colorWarning = Color.parseColor("#d29922")

    // ファイルデータ
    data class FileItem(var name: String, var content: String, var active: Boolean = false)

    private val files = mutableListOf<FileItem>()
    private var currentEditorIndex = -1

    // UI参照
    private lateinit var tabContainer: LinearLayout
    private lateinit var editorArea: EditText
    private lateinit var consoleOutput: TextView
    private lateinit var consolePanel: LinearLayout
    private lateinit var pipelinePanel: LinearLayout
    private lateinit var pipelineStatus: TextView
    private lateinit var downloadArea: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var repoDisplay: TextView
    private lateinit var tabScrollView: HorizontalScrollView
    private var consoleCollapsed = true

    // 設定
    private lateinit var prefs: SharedPreferences

    // AI翻訳状態
    private var isTranslating = false

    // プロバイダ情報
    private val providerNames = mapOf(
        "claude" to "Claude (Anthropic)",
        "openai" to "GPT (OpenAI)",
        "gemini" to "Gemini (Google)",
        "xai" to "Grok (xAI)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cloud_ide_prefs", Context.MODE_PRIVATE)
        loadFiles()
        if (files.isEmpty()) {
            files.add(FileItem("index.html", "<!DOCTYPE html>\n<html>\n<head>\n  <style>\n    body { font-family: sans-serif; }\n  </style>\n</head>\n<body>\n  <h1>Hello Cloud Build</h1>\n  <script>console.log(\"ready\");</script>\n</body>\n</html>", true))
        }
        buildUI()
        renderTabs()
        switchToActiveFile()
    }

    private fun loadFiles() {
        val count = prefs.getInt("file_count", 0)
        files.clear()
        for (i in 0 until count) {
            val name = prefs.getString("file_name_$i", "") ?: ""
            val content = prefs.getString("file_content_$i", "") ?: ""
            val active = prefs.getBoolean("file_active_$i", false)
            if (name.isNotEmpty()) {
                files.add(FileItem(name, content, active))
            }
        }
    }

    private fun saveFiles() {
        val editor = prefs.edit()
        editor.putInt("file_count", files.size)
        for (i in files.indices) {
            editor.putString("file_name_$i", files[i].name)
            editor.putString("file_content_$i", files[i].content)
            editor.putBoolean("file_active_$i", files[i].active)
        }
        editor.apply()
    }

    private fun makeRoundRect(color: Int, radius: Int = 8.dp(), strokeColor: Int = colorBorder, strokeWidth: Int = 1.dp()): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
        }

        // ヘッダー
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgSurface)
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val logo = TextView(this).apply {
            text = "⚡ IDE"
            setTextColor(colorPrimary)
            textSize = 14f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(4.dp(), 0, 8.dp(), 0)
        }
        header.addView(logo)

        // エクスポートボタン
        val exportBtn = makeHeaderButton("📦") { exportProject() }
        header.addView(exportBtn)

        // AI翻訳ボタン
        val translateBtn = makeHeaderButton("🤖") { translateToKotlin() }
        header.addView(translateBtn)

        // リポジトリ表示
        repoDisplay = TextView(this).apply {
            text = prefs.getString("selected_repo", "未接続")?.split("/")?.lastOrNull() ?: "未接続"
            setTextColor(textMuted)
            textSize = 10f
            setTypeface(Typeface.MONOSPACE)
            background = makeRoundRect(bgElevated, 10.dp())
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4.dp()
                marginEnd = 4.dp()
            }
            maxLines = 1
        }
        header.addView(repoDisplay)

        // デプロイボタン
        val deployBtn = Button(this).apply {
            text = "🚀 デプロイ"
            textSize = 12f
            setTextColor(textBright)
            background = makeRoundRect(colorPrimary, 8.dp(), colorPrimary, 0)
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.dp()).apply { marginEnd = 4.dp() }
            isAllCaps = false
            setOnClickListener { showDeployDialog() }
        }
        header.addView(deployBtn)

        // 設定ボタン
        val settingsBtn = makeHeaderButton("⚙") { showSettingsDialog() }
        header.addView(settingsBtn)

        rootLayout.addView(header)

        // ボーダー
        rootLayout.addView(makeDivider())

        // タブバー
        tabScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(bgSurface)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        tabScrollView.addView(tabContainer)
        rootLayout.addView(tabScrollView)
        rootLayout.addView(makeDivider())

        // エディタ
        editorArea = EditText(this).apply {
            setBackgroundColor(bgMain)
            setTextColor(textMain)
            textSize = 13f
            setTypeface(Typeface.MONOSPACE)
            gravity = Gravity.TOP or Gravity.START
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setHintTextColor(textMuted)
            hint = "// コードを入力..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val idx = files.indexOfFirst { it.active }
                    if (idx >= 0) {
                        files[idx].content = s.toString()
                    }
                }
            })
        }
        rootLayout.addView(editorArea)

        // パイプラインパネル
        pipelinePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSurface)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val pipelineHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 6.dp(), 16.dp(), 6.dp())
        }
        pipelineHeader.addView(TextView(this).apply {
            text = "PIPELINE"
            setTextColor(textMuted)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        pipelineHeader.addView(Button(this).apply {
            text = "✕"
            setTextColor(textMuted)
            background = null
            textSize = 14f
            setOnClickListener { pipelinePanel.visibility = View.GONE }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isAllCaps = false
        })
        pipelinePanel.addView(pipelineHeader)
        pipelinePanel.addView(makeDivider())
        pipelineStatus = TextView(this).apply {
            text = ""
            setTextColor(textMuted)
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
        }
        pipelinePanel.addView(pipelineStatus)
        rootLayout.addView(pipelinePanel)

        // コンソールパネル
        consolePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#010409"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120.dp())
        }

        val consoleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgSurface)
            setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 34.dp())
            setOnClickListener { toggleConsole() }
        }
        val consoleTitle = TextView(this).apply {
            text = "▼ Console"
            setTextColor(textMuted)
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        consoleHeader.addView(consoleTitle)

        val clearBtn = Button(this).apply {
            text = "クリア"
            textSize = 10f
            setTextColor(textMuted)
            background = makeRoundRect(bgElevated, 4.dp())
            setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 28.dp())
            isAllCaps = false
            setOnClickListener {
                consoleOutput.text = ""
            }
        }
        consoleHeader.addView(clearBtn)
        consolePanel.addView(consoleHeader)
        consolePanel.addView(makeDivider())

        val consoleScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        consoleOutput = TextView(this).apply {
            setTextColor(textMuted)
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
        }
        consoleScroll.addView(consoleOutput)
        consolePanel.addView(consoleScroll)

        // ダウンロードエリア
        downloadArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1a3a2a"))
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
            visibility = View.GONE
        }
        downloadArea.addView(TextView(this).apply {
            text = "📥 APKダウンロード可能"
            setTextColor(colorSuccess)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        consolePanel.addView(downloadArea)

        rootLayout.addView(consolePanel)

        // 初期状態でコンソール折りたたみ
        consoleCollapsed = true
        updateConsoleVisibility()

        setContentView(rootLayout)
    }

    private fun makeHeaderButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(textMuted)
            background = makeRoundRect(Color.TRANSPARENT, 8.dp())
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.dp()).apply {
                marginEnd = 2.dp()
            }
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    private fun makeDivider(): View {
        return View(this).apply {
            setBackgroundColor(colorBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp())
        }
    }

    private fun toggleConsole() {
        consoleCollapsed = !consoleCollapsed
        updateConsoleVisibility()
    }

    private fun updateConsoleVisibility() {
        val params = consolePanel.layoutParams as LinearLayout.LayoutParams
        if (consoleCollapsed) {
            params.height = 34.dp()
        } else {
            params.height = 150.dp()
        }
        consolePanel.layoutParams = params
    }

    private fun log(msg: String, type: String = "") {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "[$time] $msg\n"
            consoleOutput.append(line)
            // 自動展開
            if (consoleCollapsed) {
                consoleCollapsed = false
                updateConsoleVisibility()
            }
        }
    }

    // タブ描画
    private fun renderTabs() {
        tabContainer.removeAllViews()
        for (i in files.indices) {
            val file = files[i]
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp(), 8.dp(), 6.dp(), 8.dp())
                background = if (file.active) {
                    makeRoundRect(bgMain, 0, colorPrimary, if (file.active) 2.dp() else 0)
                } else {
                    makeRoundRect(bgSurface, 0, Color.TRANSPARENT, 0)
                }
                setOnClickListener { switchTab(i) }
                setOnLongClickListener { renameFile(i); true }
            }
            val nameView = TextView(this).apply {
                text = file.name
                setTextColor(if (file.active) textBright else textMuted)
                textSize = 12f
                setTypeface(Typeface.MONOSPACE)
                maxLines = 1
            }
            tab.addView(nameView)

            if (file.active && files.size > 1) {
                val closeView = TextView(this).apply {
                    text = " ✕"
                    setTextColor(textMuted)
                    textSize = 12f
                    setPadding(6.dp(), 0, 4.dp(), 0)
                    setOnClickListener { removeFile(i) }
                }
                tab.addView(closeView)
            }
            tabContainer.addView(tab)
        }

        // 新規タブボタン
        val addBtn = TextView(this).apply {
            text = "+ 新規"
            setTextColor(textMuted)
            textSize = 13f
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            setOnClickListener { addNewFile() }
        }
        tabContainer.addView(addBtn)
    }

    private fun switchTab(idx: Int) {
        // 現在のファイル内容を保存
        val activeIdx = files.indexOfFirst { it.active }
        if (activeIdx >= 0) {
            files[activeIdx].content = editorArea.text.toString()
        }
        files.forEachIndexed { i, f -> f.active = (i == idx) }
        renderTabs()
        editorArea.setText(files[idx].content)
        editorArea.setSelection(0)
        currentEditorIndex = idx
        saveFiles()
    }

    private fun switchToActiveFile() {
        val idx = files.indexOfFirst { it.active }
        if (idx >= 0) {
            editorArea.setText(files[idx].content)
            currentEditorIndex = idx
        }
    }

    private fun addNewFile() {
        val input = EditText(this).apply {
            hint = "ファイル名"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(textMain)
            setHintTextColor(textMuted)
        }
        AlertDialog.Builder(this)
            .setTitle("新規ファイル")
            .setView(input)
            .setPositiveButton("作成") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (files.any { it.name == name }) {
                        log("「$name」は既に存在します", "err")
                        return@setPositiveButton
                    }
                    // 現在のファイル保存
                    val activeIdx = files.indexOfFirst { it.active }
                    if (activeIdx >= 0) {
                        files[activeIdx].content = editorArea.text.toString()
                    }
                    files.forEach { it.active = false }
                    files.add(FileItem(name, "", true))
                    renderTabs()
                    editorArea.setText("")
                    saveFiles()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun renameFile(idx: Int) {
        val input = EditText(this).apply {
            setText(files[idx].name)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(textMain)
        }
        AlertDialog.Builder(this)
            .setTitle("ファイル名変更")
            .setView(input)
            .setPositiveButton("変更") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != files[idx].name) {
                    if (files.any { it.name == newName }) {
                        log("「$newName」は既に存在します", "err")
                        return@setPositiveButton
                    }
                    files[idx].name = newName
                    renderTabs()
                    saveFiles()
                    log("リネーム → $newName", "suc")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun removeFile(idx: Int) {
        if (files.size <= 1) {
            log("最後のファイルは削除できません", "err")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("削除確認")
            .setMessage("${files[idx].name} を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                files.removeAt(idx)
                if (!files.any { it.active }) {
                    files[maxOf(0, idx - 1)].active = true
                }
                renderTabs()
                switchToActiveFile()
                saveFiles()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // エクスポート（ファイル内容をログに出力）
    private fun exportProject() {
        val activeIdx = files.indexOfFirst { it.active }
        if (activeIdx >= 0) {
            files[activeIdx].content = editorArea.text.toString()
        }
        saveFiles()
        log("プロジェクト保存完了（${files.size}ファイル）", "suc")
        val sb = StringBuilder()
        files.forEach { sb.append("── ${it.name} (${it.content.length}文字) ──\n") }
        log(sb.toString())
        Toast.makeText(this, "プロジェクトを保存しました", Toast.LENGTH_SHORT).show()
    }

    // 設定ダイアログ
    private fun showSettingsDialog() {
        val scrollView = ScrollView(this).apply {
            setPadding(20.dp(), 16.dp(), 20.dp(), 16.dp())
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // GitHubトークン
        layout.addView(makeLabel("GITHUB TOKEN"))
        val tokenInput = EditText(this).apply {
            setText(prefs.getString("gh_token", ""))
            hint = "ghp_..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(textMain)
            setHintTextColor(textMuted)
            background = makeRoundRect(bgElevated, 8.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setTypeface(Typeface.MONOSPACE)
        }
        layout.addView(tokenInput)
        layout.addView(makeSpacer(12))

        // リポジトリ名
        layout.addView(makeLabel("リポジトリ (owner/repo)"))
        val repoInput = EditText(this).apply {
            setText(prefs.getString("selected_repo", ""))
            hint = "owner/repo"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(textMain)
            setHintTextColor(textMuted)
            background = makeRoundRect(bgElevated, 8.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setTypeface(Typeface.MONOSPACE)
        }
        layout.addView(repoInput)
        layout.addView(makeSpacer(20))

        // AIプロバイダ
        layout.addView(makeLabel("🤖 AI PROVIDER"))
        val providerSpinner = Spinner(this).apply {
            background = makeRoundRect(bgElevated, 8.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        }
        val providerKeys = listOf("claude", "openai", "gemini", "xai")
        val providerLabels = providerKeys.map { providerNames[it] ?: it }
        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providerLabels)
        val savedProvider = prefs.getString("ai_provider", "claude") ?: "claude"
        providerSpinner.setSelection(providerKeys.indexOf(savedProvider).coerceAtLeast(0))
        layout.addView(providerSpinner)
        layout.addView(makeSpacer(12))

        // APIキー
        layout.addView(makeLabel("API KEY"))
        val apiKeyInput = EditText(this).apply {
            setText(prefs.getString("apiKey_$savedProvider", ""))
            hint = "APIキーを入力..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(textMain)
            setHintTextColor(textMuted)
            background = makeRoundRect(bgElevated, 8.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setTypeface(Typeface.MONOSPACE)
        }
        layout.addView(apiKeyInput)
        layout.addView(makeSpacer(12))

        // プロバイダ変更時にAPIキーフィールドを更新
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val key = providerKeys[position]
                apiKeyInput.setText(prefs.getString("apiKey_$key", ""))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scrollView)
            .setPositiveButton("💾 保存") { _, _ ->
                val token = tokenInput.text.toString()
                val repo = repoInput.text.toString().trim()
                val providerIdx = providerSpinner.selectedItemPosition
                val provider = providerKeys[providerIdx]
                val apiKey = apiKeyInput.text.toString()

                prefs.edit()
                    .putString("gh_token", token)
                    .putString("selected_repo", repo)
                    .putString("ai_provider", provider)
                    .putString("apiKey_$provider", apiKey)
                    .apply()

                repoDisplay.text = if (repo.isNotEmpty()) repo.split("/").lastOrNull() ?: "未接続" else "未接続"
                log("設定を保存しました", "suc")
            }
            .setNegativeButton("キャンセル", null)
            .setNeutralButton("🗑 初期化") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("データ初期化")
                    .setMessage("すべてのデータを削除します。よろしいですか？")
                    .setPositiveButton("初期化") { _, _ -> clearAllData() }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            .show()
    }

    // デプロイダイアログ
    private fun showDeployDialog() {
        val repo = prefs.getString("selected_repo", "") ?: ""
        if (repo.isEmpty()) {
            Toast.makeText(this, "設定画面でリポジトリを設定してください", Toast.LENGTH_SHORT).show()
            return
        }

        val scrollView = ScrollView(this).apply {
            setPadding(20.dp(), 16.dp(), 20.dp(), 16.dp())
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layout.addView(makeLabel("アプリ名"))
        val appNameInput = EditText(this).apply {
            setText(prefs.getString("app_name", "My App"))
            setTextColor(textMain)
            background = makeRoundRect(bgElevated, 8.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        }
        layout.addView(appNameInput)
        layout.addView(makeSpacer(12))

        layout.addView(makeLabel("パッケージID"))
        val appIdInput = EditText(this).apply {
            setText(prefs.getString("app_id", "com.example.app"))
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(textMain)
            background = makeRoundRect(bgElevated, 8.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setTypeface(Typeface.MONOSPACE)
        }
        layout.addView(appIdInput)
        layout.addView(makeSpacer(12))

        layout.addView(makeLabel("ブランチ"))
        val branchInput = EditText(this).apply {
            setText(prefs.getString("branch_name", "main"))
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(textMain)
            background = makeRoundRect(bgElevated, 8.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setTypeface(Typeface.MONOSPACE)
        }
        layout.addView(branchInput)
        layout.addView(makeSpacer(8))

        layout.addView(TextView(this).apply {
            text = "リポジトリ: $repo"
            setTextColor(textBright)
            textSize = 13f
            setTypeface(Typeface.MONOSPACE)
        })

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("🚀 デプロイ設定")
            .setView(scrollView)
            .setPositiveButton("デプロイ実行") { _, _ ->
                val appName = appNameInput.text.toString().ifEmpty { "My App" }
                val appId = appIdInput.text.toString().ifEmpty { "com.example.app" }
                val branch = branchInput.text.toString().ifEmpty { "main" }

                val err = validateAppId(appId)
                if (err != null) {
                    log(err, "err")
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putString("app_name", appName)
                    .putString("app_id", appId)
                    .putString("branch_name", branch)
                    .apply()

                executeDeploy(appName, appId, branch, repo)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun validateAppId(appId: String): String? {
        val regex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        if (!regex.matches(appId)) return "パッケージIDが不正です。例: com.example.app"
        val reserved = setOf("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while")
        for (s in appId.split(".")) {
            if (s in reserved) return "予約語「$s」は使えません"
        }
        return null
    }

    // GitHub API呼び出し
    private fun ghApiCall(path: String, method: String = "GET", body: String? = null, token: String, repo: String): String {
        val urlStr = if (path.startsWith("http")) path else "https://api.github.com/repos/$repo$path"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "token $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30000
        conn.readTimeout = 30000

        if (body != null) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = if (stream != null) {
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } else ""

        if (code !in 200..299 && code != 204) {
            throw Exception("HTTP $code: ${response.take(200)}")
        }
        return response
    }

    // Base64エンコード
    private fun base64Encode(text: String): String {
        return android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }

    // デプロイ実行
    private fun executeDeploy(appName: String, appId: String, branch: String, repo: String) {
        val token = prefs.getString("gh_token", "") ?: ""
        if (token.isEmpty()) {
            log("GitHubトークンが未設定です", "err")
            return
        }

        // 現在のファイル内容を保存
        val activeIdx = files.indexOfFirst { it.active }
        if (activeIdx >= 0) {
            files[activeIdx].content = editorArea.text.toString()
        }
        saveFiles()

        pipelinePanel.visibility = View.VISIBLE
        pipelineStatus.text = "📦 Upload → 📝 Commit → ⚡ Trigger → 🔨 Build → 📥 APK"
        log("$repo ($branch) へデプロイ開始")

        lifecycleScope.launch {
            try {
                // Step 1: Webアセットのコミット
                pipelineStatus.text = "📦 Webアセットアップロード中..."
                log("Webアセットをアップロード中...")
                withContext(Dispatchers.IO) {
                    for (file in files.filter { !it.name.endsWith(".kt") }) {
                        val path = "/contents/app/src/main/assets/${file.name}"
                        val contentB64 = base64Encode(file.content)
                        var sha: String? = null
                        try {
                            val existing = ghApiCall(path, "GET", null, token, repo)
                            val json = JSONObject(existing)
                            sha = json.optString("sha", null)
                        } catch (_: Exception) {}
                        val body = JSONObject().apply {
                            put("message", "chore: update ${file.name}")
                            put("content", contentB64)
                            if (sha != null) put("sha", sha)
                        }
                        ghApiCall(path, "PUT", body.toString(), token, repo)
                    }
                }
                log("Webアセットアップロード完了", "suc")

                // Step 2: Kotlin ファイルのコミット
                val ktFiles = files.filter { it.name.endsWith(".kt") }
                if (ktFiles.isNotEmpty()) {
                    pipelineStatus.text = "📝 Kotlinファイルコミット中..."
                    log("Kotlinファイルをコミット中...")
                    val ktBase = "app/src/main/java/${appId.replace(".", "/")}"
                    withContext(Dispatchers.IO) {
                        for (file in ktFiles) {
                            val path = "/contents/$ktBase/${file.name}"
                            val contentB64 = base64Encode(file.content)
                            var sha: String? = null
                            try {
                                val existing = ghApiCall(path, "GET", null, token, repo)
                                val json = JSONObject(existing)
                                sha = json.optString("sha", null)
                            } catch (_: Exception) {}
                            val body = JSONObject().apply {
                                put("message", "chore: update ${file.name}")
                                put("content", contentB64)
                                if (sha != null) put("sha", sha)
                            }
                            ghApiCall(path, "PUT", body.toString(), token, repo)
                        }
                    }
                    log("Kotlinファイルコミット完了 (${ktFiles.size}件)", "suc")
                }

                // Step 3: ワークフローYAMLのコミット
                pipelineStatus.text = "⚡ ワークフロー設定中..."
                log("build-apk.yml をコミット中...")
                withContext(Dispatchers.IO) {
                    val ymlPath = "/contents/.github/workflows/build-apk.yml"
                    val ymlContent = getBuildYml()
                    val ymlB64 = base64Encode(ymlContent)
                    var ymlSha: String? = null
                    try {
                        val existing = ghApiCall(ymlPath, "GET", null, token, repo)
                        val json = JSONObject(existing)
                        ymlSha = json.optString("sha", null)
                    } catch (_: Exception) {}
                    val body = JSONObject().apply {
                        put("message", "chore: update build-apk.yml")
                        put("content", ymlB64)
                        if (ymlSha != null) put("sha", ymlSha)
                    }
                    ghApiCall(ymlPath, "PUT", body.toString(), token, repo)
                }
                log("build-apk.yml コミット完了", "suc")

                // Step 4: ワークフローディスパッチ
                pipelineStatus.text = "⚡ ビルドトリガー中..."
                log("ビルドをトリガー中...")
                withContext(Dispatchers.IO) {
                    val body = JSONObject().apply {
                        put("ref", branch)
                        put("inputs", JSONObject().apply {
                            put("app_name", appName)
                            put("app_id", appId)
                        })
                    }
                    ghApiCall("/actions/workflows/build-apk.yml/dispatches", "POST", body.toString(), token, repo)
                }
                log("ビルドトリガー完了", "suc")

                // Step 5: ポーリング開始
                pipelineStatus.text = "🔨 ビルド中... Run ID取得中"
                log("Run IDを取得中...")
                kotlinx.coroutines.delay(5000)

                val runId = withContext(Dispatchers.IO) {
                    val response = ghApiCall("/actions/workflows/build-apk.yml/runs?per_page=1", "GET", null, token, repo)
                    val json = JSONObject(response)
                    val runs = json.getJSONArray("workflow_runs")
                    if (runs.length() > 0) runs.getJSONObject(0).getLong("id") else null
                }

                if (runId == null) {
                    log("Run IDが取得できませんでした", "err")
                    pipelineStatus.text = "❌ Run ID取得失敗"
                    return@launch
                }

                log("Run #$runId 監視開始")
                startPolling(runId, token, repo)

            } catch (e: Exception) {
                log("デプロイエラー: ${e.message}", "err")
                pipelineStatus.text = "❌ エラー: ${e.message?.take(50)}"
            }
        }
    }

    private fun startPolling(runId: Long, token: String, repo: String) {
        lifecycleScope.launch {
            var pollCount = 0
            val maxPolls = 180
            while (pollCount < maxPolls) {
                pollCount++
                kotlinx.coroutines.delay(10000)
                try {
                    val response = withContext(Dispatchers.IO) {
                        ghApiCall("/actions/runs/$runId", "GET", null, token, repo)
                    }
                    val run = JSONObject(response)
                    val status = run.getString("status")
                    val conclusion = run.optString("conclusion", "")

                    pipelineStatus.text = "🔨 ビルド中... ($status)"
                    log("状態: $status${if (conclusion.isNotEmpty()) " ($conclusion)" else " (実行中)"}")

                    if (status == "completed") {
                        if (conclusion == "success") {
                            pipelineStatus.text = "✅ ビルド成功！"
                            log("ビルド成功！", "suc")
                            showDownloadInfo(runId, token, repo)
                        } else {
                            pipelineStatus.text = "❌ ビルド失敗: $conclusion"
                            log("ビルド失敗: $conclusion", "err")
                            val htmlUrl = run.optString("html_url", "")
                            if (htmlUrl.isNotEmpty()) {
                                log("詳細: $htmlUrl")
                            }
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    log("監視エラー: ${e.message}", "err")
                }
            }
            pipelineStatus.text = "⏰ タイムアウト"
            log("30分タイムアウト", "err")
        }
    }

    private fun showDownloadInfo(runId: Long, token: String, repo: String) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ghApiCall("/actions/runs/$runId/artifacts", "GET", null, token, repo)
                }
                val json = JSONObject(response)
                val artifacts = json.getJSONArray("artifacts")
                if (artifacts.length() > 0) {
                    val artifact = artifacts.getJSONObject(0)
                    val downloadUrl = artifact.getString("archive_download_url")
                    log("📥 APK準備完了", "suc")
                    log("ダウンロードURL: $downloadUrl")
                    log("GitHub Actions画面: https://github.com/$repo/actions/runs/$runId")
                    runOnUiThread {
                        downloadArea.visibility = View.VISIBLE
                        pipelineStatus.text = "✅ APK準備完了！Consoleでダウンロード情報を確認"
                    }
                } else {
                    log("Artifactが見つかりませんでした", "err")
                }
            } catch (e: Exception) {
                log("Artifact取得エラー: ${e.message}", "err")
            }
        }
    }

    // AI翻訳
    private fun translateToKotlin() {
        if (isTranslating) {
            log("翻訳処理が既に実行中です", "err")
            return
        }

        val provider = prefs.getString("ai_provider", "claude") ?: "claude"
        val apiKey = prefs.getString("apiKey_$provider", "") ?: ""
        if (apiKey.isEmpty()) {
            log("${providerNames[provider]}のAPIキーが未設定です。設定画面を開きます", "err")
            showSettingsDialog()
            return
        }

        val htmlFile = files.find { it.name == "index.html" }
        if (htmlFile == null) {
            log("index.html が見つかりません", "err")
            return
        }

        // 現在のファイル内容を保存
        val activeIdx = files.indexOfFirst { it.active }
        if (activeIdx >= 0) {
            files[activeIdx].content = editorArea.text.toString()
            if (files[activeIdx].name == "index.html") {
                // editorAreaから最新の内容を取得
            }
        }

        isTranslating = true
        pipelinePanel.visibility = View.VISIBLE
        pipelineStatus.text = "🤖 ${providerNames[provider]}で翻訳中..."
        log("${providerNames[provider]}で翻訳開始")

        lifecycleScope.launch {
            try {
                val systemPrompt = getKotlinTranslationPrompt()
                val userPrompt = "以下のHTMLをMainActivity.ktに変換してください:\n\n${htmlFile.content}"

                val result = withContext(Dispatchers.IO) {
                    callAI(provider, apiKey, systemPrompt, userPrompt)
                }

                val kotlinCode = stripCodeFence(result)
                if (kotlinCode.length < 50) {
                    throw Exception("生成されたコードが短すぎます")
                }

                // MainActivity.ktを更新または追加
                val existing = files.indexOfFirst { it.name == "MainActivity.kt" }
                if (existing >= 0) {
                    files[existing].content = kotlinCode
                    files.forEach { it.active = false }
                    files[existing].active = true
                } else {
                    files.forEach { it.active = false }
                    files.add(FileItem("MainActivity.kt", kotlinCode, true))
                }

                renderTabs()
                editorArea.setText(kotlinCode)
                saveFiles()
                pipelineStatus.text = "✅ AI翻訳完了"
                log("MainActivity.kt を生成しました", "suc")

            } catch (e: Exception) {
                pipelineStatus.text = "❌ 翻訳エラー"
                log("翻訳エラー: ${e.message}", "err")
            } finally {
                isTranslating = false
            }
        }
    }

    private fun callAI(provider: String, apiKey: String, systemPrompt: String, userPrompt: String): String {
        return when (provider) {
            "claude" -> callClaude(apiKey, systemPrompt, userPrompt)
            "openai" -> callOpenAI(apiKey, systemPrompt, userPrompt)
            "gemini" -> callGemini(apiKey, systemPrompt, userPrompt)
            "xai" -> callXai(apiKey, systemPrompt, userPrompt)
            else -> throw Exception("未知のプロバイダ: $provider")
        }
    }

    private fun callClaude(apiKey: String, systemPrompt: String, userPrompt: String): String {
        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.setRequestProperty("content-type", "application/json")
        conn.connectTimeout = 120000
        conn.readTimeout = 120000
        conn.doOutput = true

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 32000)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (code !in 200..299) throw Exception("[Claude] $code: ${response.take(200)}")

        val json = JSONObject(response)
        val content = json.getJSONArray("content")
        if (content.length() == 0) throw Exception("[Claude] 応答が空です")
        return content.getJSONObject(0).getString("text")
    }

    private fun callOpenAI(apiKey: String, systemPrompt: String, userPrompt: String): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 120000
        conn.readTimeout = 120000
        conn.doOutput = true

        val body = JSONObject().apply {
            put("model", "gpt-4o")
            put("max_tokens", 32000)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (code !in 200..299) throw Exception("[OpenAI] $code: ${response.take(200)}")

        val json = JSONObject(response)
        val choices = json.getJSONArray("choices")
        if (choices.length() == 0) throw Exception("[OpenAI] 応答が空です")
        return choices.getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun callGemini(apiKey: String, systemPrompt: String, userPrompt: String): String {
        val model = "gemini-2.5-flash"
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 120000
        conn.readTimeout = 120000
        conn.doOutput = true

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", userPrompt) }) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 32000)
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (code !in 200..299) throw Exception("[Gemini] $code: ${response.take(200)}")

        val json = JSONObject(response)
        val candidates = json.getJSONArray("candidates")
        if (candidates.length() == 0) throw Exception("[Gemini] 応答が空です")
        val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text")) return part.getString("text")
        }
        throw Exception("[Gemini] テキスト応答がありません")
    }

    private fun callXai(apiKey: String, systemPrompt: String, userPrompt: String): String {
        val url = URL("https://api.x.ai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 120000
        conn.readTimeout = 120000
        conn.doOutput = true

        val body = JSONObject().apply {
            put("model", "grok-2-latest")
            put("max_tokens", 32000)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (code !in 200..299) throw Exception("[xAI] $code: ${response.take(200)}")

        val json = JSONObject(response)
        val choices = json.getJSONArray("choices")
        if (choices.length() == 0) throw Exception("[xAI] 応答が空です")
        return choices.getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun stripCodeFence(text: String): String {
        var t = text.trim()
        t = t.replace(Regex("^```(?:kotlin|kt|java)?\\s*\\n?", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\n?```\\s*$", RegexOption.IGNORE_CASE), "")
        return t.trim()
    }

    private fun getKotlinTranslationPrompt(): String {
        return "あなたはAndroidネイティブ開発の専門家です。提供されるHTML/CSS/JavaScriptのUIを、Android Native (Kotlin) の MainActivity.kt に変換してください。WebViewは使わず、setContentViewでプログラマティックにUI構築してください。パッケージ名は com.example.app。出力はMainActivity.ktの中身のみ。コードフェンスは含めないでください。AppCompatActivityを継承すること。"
    }

    private fun getBuildYml(): String {
        return """name: Build APK
on:
  workflow_dispatch:
    inputs:
      app_name:
        description: 'App display name'
        required: false
        type: string
        default: 'My App'
      app_id:
        description: 'Application ID'
        required: false
        type: string
        default: 'com.example.app'
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      - name: Locate MainActivity.kt
        id: locate_kt
        run: |
          KT_PATH=${'$'}(find . -type f -name 'MainActivity.kt' ! -path './.git/*' | head -n 1)
          if [ -z "${'$'}KT_PATH" ]; then exit 1; fi
          echo "kt_path=${'$'}KT_PATH" >> "${'$'}GITHUB_OUTPUT"
      - name: Generate project
        env:
          APP_NAME: ${'$'}{{ inputs.app_name }}
          APP_ID: ${'$'}{{ inputs.app_id }}
          KT_SOURCE: ${'$'}{{ steps.locate_kt.outputs.kt_path }}
        run: |
          PROJ=/tmp/androidproj
          mkdir -p "${'$'}PROJ" && cd "${'$'}PROJ"
          PKG_DIR=${'$'}(echo "${'$'}APP_ID" | tr '.' '/')
          mkdir -p "app/src/main/java/${'$'}PKG_DIR" app/src/main/res/values
          SRC_KT="${'$'}GITHUB_WORKSPACE/${'$'}KT_SOURCE"
          DEST_KT="app/src/main/java/${'$'}PKG_DIR/MainActivity.kt"
          sed "1s|^package .*|package ${'$'}APP_ID|" "${'$'}SRC_KT" > "${'$'}DEST_KT"
          cat > settings.gradle <<'EOF'
          pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
          dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
          rootProject.name = "App"
          include ':app'
          EOF
          cat > build.gradle <<'EOF'
          plugins { id 'com.android.application' version '8.2.2' apply false; id 'org.jetbrains.kotlin.android' version '1.9.22' apply false }
          EOF
          cat > app/build.gradle <<APPEOF
          plugins { id 'com.android.application'; id 'org.jetbrains.kotlin.android' }
          android { namespace '${'$'}APP_ID'; compileSdk 34; defaultConfig { applicationId "${'$'}APP_ID"; minSdk 24; targetSdk 34; versionCode 1; versionName "1.0" }; compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = '17' } }
          dependencies { implementation 'androidx.core:core-ktx:1.12.0'; implementation 'androidx.appcompat:appcompat:1.6.1'; implementation 'com.google.android.material:material:1.11.0'; implementation 'androidx.constraintlayout:constraintlayout:2.1.4'; implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'; implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3' }
          APPEOF
          cat > app/src/main/AndroidManifest.xml <<'EOF'
          <?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET"/><application android:allowBackup="true" android:label="@string/app_name" android:theme="@style/Theme.App" android:supportsRtl="true"><activity android:name=".MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>
          EOF
          cat > app/src/main/res/values/strings.xml <<SEOF
          <?xml version="1.0" encoding="utf-8"?><resources><string name="app_name">${'$'}APP_NAME</string></resources>
          SEOF
          cat > app/src/main/res/values/themes.xml <<'EOF'
          <?xml version="1.0" encoding="utf-8"?><resources><style name="Theme.App" parent="Theme.Material3.DayNight.NoActionBar"/></resources>
          EOF
          cat > gradle.properties <<'EOF'
          org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
          android.useAndroidX=true
          android.nonTransitiveRClass=true
          EOF
      - uses: gradle/actions/setup-gradle@v3
      - name: Build
        working-directory: /tmp/androidproj
        run: gradle wrapper --gradle-version 8.5 && ./gradlew assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: /tmp/androidproj/app/build/outputs/apk/debug/*.apk
"""
    }

    private fun clearAllData() {
        prefs.edit().clear().apply()
        files.clear()
        files.add(FileItem("index.html", "<!DOCTYPE html>\n<html>\n<body>\n  <h1>Hello</h1>\n</body>\n</html>", true))
        renderTabs()
        switchToActiveFile()
        consoleOutput.text = ""
        downloadArea.visibility = View.GONE
        pipelinePanel.visibility = View.GONE
        repoDisplay.text = "未接続"
        log("初期化完了", "suc")
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(textMuted)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 5.dp())
        }
    }

    private fun makeSpacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightDp.dp())
        }
    }

    override fun onPause() {
        super.onPause()
        val activeIdx = files.indexOfFirst { it.active }
        if (activeIdx >= 0) {
            files[activeIdx].content = editorArea.text.toString()
        }
        saveFiles()
    }
}