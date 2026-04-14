package com.example.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ─── データクラス ───
data class FileEntry(val name: String, var content: String, var active: Boolean = false)

class MainActivity : AppCompatActivity() {

    // ─── カラー定数 ───
    private val bgMain = Color.parseColor("#0d1117")
    private val bgSurface = Color.parseColor("#161b22")
    private val bgElevated = Color.parseColor("#21262d")
    private val textMain = Color.parseColor("#c9d1d9")
    private val textMuted = Color.parseColor("#6e7681")
    private val textBright = Color.parseColor("#f0f6fc")
    private val primary = Color.parseColor("#58a6ff")
    private val borderColor = Color.parseColor("#30363d")
    private val successColor = Color.parseColor("#3fb950")
    private val errorColor = Color.parseColor("#f85149")
    private val warningColor = Color.parseColor("#d29922")

    // ─── 状態 ───
    private val files = mutableListOf(
        FileEntry("index.html", "<!DOCTYPE html>\n<html>\n<body>\n  <h1>Hello Cloud Build</h1>\n</body>\n</html>", true)
    )
    private var activeFileIndex = 0
    private var isTranslating = false

    // ─── AI設定 ───
    private val aiProviders = listOf("claude", "openai", "gemini", "xai")
    private val aiProviderNames = mapOf(
        "claude" to "Claude (Anthropic)",
        "openai" to "GPT (OpenAI)",
        "gemini" to "Gemini (Google)",
        "xai" to "Grok (xAI)"
    )
    private val aiDefaultModels = mapOf(
        "claude" to "claude-sonnet-4-6",
        "openai" to "gpt-4o",
        "gemini" to "gemini-2.5-flash",
        "xai" to "grok-2-latest"
    )

    // ─── ウィジェット参照 ───
    private lateinit var tabContainer: LinearLayout
    private lateinit var editorArea: EditText
    private lateinit var logConsole: LinearLayout
    private lateinit var logScrollView: ScrollView
    private lateinit var activeRepoDisplay: TextView
    private lateinit var deployBtn: Button
    private lateinit var consolePanel: LinearLayout
    private lateinit var pipelinePanel: LinearLayout
    private lateinit var pipelineStepsLayout: LinearLayout
    private lateinit var pipelineElapsedText: TextView

    // ─── パイプライン ───
    private var pipelineTimer: Timer? = null
    private var pipelineStart: Long = 0

    // ─── SharedPreferences ───
    private val prefs by lazy { getSharedPreferences("cloud_ide_prefs", Context.MODE_PRIVATE) }

    private val kotlinTranslationPrompt = """あなたはAndroidネイティブ開発の専門家です。
提供されるHTML/CSS/JavaScriptのUIを、Android Native (Kotlin) の MainActivity.kt に変換してください。

厳守事項:
1. WebViewは絶対に使わない
2. UI構築はXMLレイアウトファイルを使わず、setContentViewにLinearLayout/ConstraintLayout/TextView/Button/EditTextをコードのみでプログラマティックに構築する単一ファイル構成にする
3. JavaScriptのイベントハンドラはsetOnClickListenerなどに変換
4. 非同期処理はKotlin Coroutines (lifecycleScope.launch)
5. アラートはToast.makeText
6. fetch等のHTTPはOkHttpではなくjava.net.HttpURLConnectionで記述（依存追加不要にするため）
7. パッケージ名は com.example.app
8. 出力はMainActivity.ktの中身のみ。説明文・マークダウン記法・コードフェンス(```)は一切含めない
9. importは必要なものをすべて記載
10. コメントは日本語で簡潔に"""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ─── ルートレイアウト構築 ───
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ─── ヘッダー ───
        rootLayout.addView(buildHeader())

        // ─── タブバー ───
        val tabScrollView = HorizontalScrollView(this).apply {
            setBackgroundColor(bgSurface)
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
            )
        }
        tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabScrollView.addView(tabContainer)
        rootLayout.addView(tabScrollView)

        // ─── エディタ領域 ───
        editorArea = EditText(this).apply {
            setBackgroundColor(bgMain)
            setTextColor(textMain)
            setHintTextColor(textMuted)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(8), dp(12), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val editorScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        editorScroll.addView(editorArea)
        rootLayout.addView(editorScroll)

        // ─── パイプラインパネル ───
        pipelinePanel = buildPipelinePanel()
        pipelinePanel.visibility = View.GONE
        rootLayout.addView(pipelinePanel)

        // ─── コンソールパネル ───
        consolePanel = buildConsolePanel()
        rootLayout.addView(consolePanel)

        setContentView(rootLayout)

        // ─── 初期化 ───
        loadFromPrefs()
        renderTabs()
        loadActiveFileToEditor()
    }

    // ─── ヘッダー構築 ───
    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgSurface)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )

            // ロゴ
            addView(TextView(this@MainActivity).apply {
                text = "⚡ IDE"
                setTextColor(primary)
                textSize = 13f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(0, 0, dp(8), 0)
            })

            // ZIPエクスポートボタン
            addView(makeSmallGhostButton("📦") { exportFiles() })

            // 翻訳ボタン
            addView(makeSmallGhostButton("🤖") { translateToKotlin() })

            // スペーサー
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

            // リポジトリ表示
            activeRepoDisplay = TextView(this@MainActivity).apply {
                text = "未接続"
                setTextColor(textMuted)
                textSize = 10f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setBackgroundColor(bgElevated)
                maxWidth = dp(90)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setSingleLine(true)
            }
            addView(activeRepoDisplay)

            // デプロイボタン
            deployBtn = Button(this@MainActivity).apply {
                text = "🚀 デプロイ"
                setTextColor(textBright)
                setBackgroundColor(primary)
                textSize = 12f
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(36)
                ).apply { marginStart = dp(6) }
                setOnClickListener { openDeployDialog() }
            }
            addView(deployBtn)

            // 設定ボタン
            addView(makeSmallGhostButton("⚙") { openSettings() }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    marginStart = dp(4)
                }
            })
        }
    }

    // ─── パイプラインパネル構築 ───
    private fun buildPipelinePanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSurface)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // パイプラインヘッダー
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(6), dp(16), dp(6))
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(this@MainActivity).apply {
                    text = "PIPELINE"
                    setTextColor(textMuted)
                    textSize = 11f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(this@MainActivity).apply {
                    text = "✕"
                    setTextColor(textMuted)
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 14f
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(32)
                    )
                    setOnClickListener { pipelinePanel.visibility = View.GONE }
                })
            })

            // ステップ表示
            pipelineStepsLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(pipelineStepsLayout)
            })

            // 経過時間
            pipelineElapsedText = TextView(this@MainActivity).apply {
                setTextColor(textMuted)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
                setPadding(dp(16), 0, dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            addView(pipelineElapsedText)
        }
    }

    // ─── コンソールパネル構築 ───
    private fun buildConsolePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#010409"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(160)
            )
        }

        // コンソールヘッダー
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgSurface)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(this@MainActivity).apply {
                text = "▼ Console"
                setTextColor(textMuted)
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@MainActivity).apply {
                text = "クリア"
                setTextColor(textMuted)
                setBackgroundColor(bgElevated)
                textSize = 10f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)
                )
                setOnClickListener { logConsole.removeAllViews() }
            })
        }
        panel.addView(header)

        // ログ表示エリア
        logConsole = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        logScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(logConsole)
        }
        panel.addView(logScrollView)

        return panel
    }

    // ─── タブ再描画 ───
    private fun renderTabs() {
        tabContainer.removeAllViews()
        files.forEachIndexed { index, file ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setBackgroundColor(if (file.active) bgMain else bgSurface)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
                )
                setOnClickListener { switchTab(index) }
            }
            tab.addView(TextView(this).apply {
                text = file.name
                setTextColor(if (file.active) textBright else textMuted)
                textSize = 12f
                typeface = Typeface.MONOSPACE
            })
            // 閉じるボタン
            tab.addView(Button(this).apply {
                text = "✕"
                setTextColor(textMuted)
                setBackgroundColor(Color.TRANSPARENT)
                textSize = 12f
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener { removeFile(index) }
            })
            tabContainer.addView(tab)
        }

        // 新規ファイルボタン
        tabContainer.addView(TextView(this).apply {
            text = "+ 新規"
            setTextColor(textMuted)
            textSize = 12f
            setPadding(dp(14), 0, dp(14), 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            )
            setOnClickListener { addNewFile() }
        })
    }

    // ─── タブ切り替え ───
    private fun switchTab(index: Int) {
        // 現在のエディタ内容を保存
        if (activeFileIndex < files.size) {
            files[activeFileIndex].content = editorArea.text.toString()
        }
        files.forEachIndexed { i, f -> f.active = (i == index) }
        activeFileIndex = index
        renderTabs()
        loadActiveFileToEditor()
        saveToPrefs()
    }

    // ─── アクティブファイルをエディタに読み込む ───
    private fun loadActiveFileToEditor() {
        val active = files.getOrNull(activeFileIndex)
        editorArea.setText(active?.content ?: "")
        editorArea.setSelection(0)
    }

    // ─── 新規ファイル追加 ───
    private fun addNewFile() {
        val input = EditText(this).apply {
            hint = "ファイル名"
            setTextColor(textMain)
            setBackgroundColor(bgElevated)
            setHintTextColor(textMuted)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("新規ファイル")
            .setView(input)
            .setPositiveButton("作成") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    showToast("ファイル名を入力してください")
                    return@setPositiveButton
                }
                if (files.any { it.name == name }) {
                    showToast("「$name」は既に存在します")
                    return@setPositiveButton
                }
                // 現在の内容を保存
                if (activeFileIndex < files.size) {
                    files[activeFileIndex].content = editorArea.text.toString()
                }
                files.forEach { it.active = false }
                files.add(FileEntry(name, "", true))
                activeFileIndex = files.size - 1
                renderTabs()
                loadActiveFileToEditor()
                saveToPrefs()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ─── ファイル削除 ───
    private fun removeFile(index: Int) {
        if (files.size == 1) {
            showToast("最後のファイルは削除できません")
            return
        }
        // 現在の内容を保存
        if (activeFileIndex < files.size) {
            files[activeFileIndex].content = editorArea.text.toString()
        }
        files.removeAt(index)
        activeFileIndex = if (activeFileIndex >= files.size) files.size - 1 else activeFileIndex
        files[activeFileIndex].active = true
        renderTabs()
        loadActiveFileToEditor()
        saveToPrefs()
    }

    // ─── ファイルエクスポート（テキスト表示） ───
    private fun exportFiles() {
        if (activeFileIndex < files.size) {
            files[activeFileIndex].content = editorArea.text.toString()
        }
        val sb = StringBuilder()
        files.forEach { f ->
            sb.appendLine("=== ${f.name} ===")
            sb.appendLine(f.content)
            sb.appendLine()
        }
        showLargeTextDialog("エクスポート (${files.size}ファイル)", sb.toString())
    }

    // ─── デプロイダイアログ ───
    private fun openDeployDialog() {
        val repo = prefs.getString("selected_repo", "") ?: ""
        if (repo.isEmpty()) {
            showToast("設定画面でリポジトリを選択してください")
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(bgSurface)
        }

        layout.addView(TextView(this).apply {
            text = "🚀 デプロイ設定"
            setTextColor(textBright)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
        })

        // アプリ名
        layout.addView(makeLabel("アプリ名"))
        val appNameEdit = makeInputField(prefs.getString("app_name", "My App") ?: "My App")
        layout.addView(appNameEdit)

        // パッケージID
        layout.addView(makeLabel("パッケージID"))
        val appIdEdit = makeInputField(prefs.getString("app_id", "com.example.app") ?: "com.example.app").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layout.addView(appIdEdit)

        layout.addView(TextView(this).apply {
            text = "例: com.example.app"
            setTextColor(textMuted)
            textSize = 10f
            setPadding(0, dp(2), 0, dp(8))
        })

        // ブランチ
        layout.addView(makeLabel("ブランチ"))
        val branchEdit = makeInputField(prefs.getString("branch_name", "main") ?: "main")
        layout.addView(branchEdit)

        // リポジトリ表示
        layout.addView(makeLabel("リポジトリ"))
        layout.addView(TextView(this).apply {
            text = repo
            setTextColor(textBright)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        })

        val scrollView = ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setView(scrollView)
            .setPositiveButton("デプロイ実行") { _, _ ->
                val appName = appNameEdit.text.toString().ifEmpty { "My App" }
                val appId = appIdEdit.text.toString().ifEmpty { "com.example.app" }
                val branch = branchEdit.text.toString().trim().ifEmpty { "main" }

                val err = validateAppId(appId)
                if (err != null) {
                    showToast(err)
                    return@setPositiveButton
                }

                prefs.edit().apply {
                    putString("app_name", appName)
                    putString("app_id", appId)
                    putString("branch_name", branch)
                    apply()
                }

                executeDeploy(appName, appId, branch, repo)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ─── デプロイ実行 ───
    private fun executeDeploy(appName: String, appId: String, branch: String, repo: String) {
        val token = prefs.getString("gh_token", "") ?: ""
        if (token.isEmpty()) {
            showToast("GitHubトークンが未設定です")
            return
        }

        // 現在のエディタ内容を保存
        if (activeFileIndex < files.size) {
            files[activeFileIndex].content = editorArea.text.toString()
        }

        pipelinePanel.visibility = View.VISIBLE
        openPipeline()
        deployBtn.isEnabled = false

        lifecycleScope.launch {
            try {
                log("$repo ($branch) へデプロイ開始")

                // Step1: ファイルをコミット
                setStep("upload", "active", "送信中...")
                val ktFiles = files.filter { it.name.endsWith(".kt") }
                val webFiles = files.filter { !it.name.endsWith(".kt") }

                // Webファイルをアップロード
                for (f in webFiles) {
                    uploadFileToGitHub(token, repo, f.name, f.content, branch)
                    log("${f.name} アップロード完了")
                }
                setStep("upload", "done", "完了")

                // Step2: Kotlinファイルをコミット
                val ktBase = "app/src/main/java/${appId.replace('.', '/')}"
                setStep("commit", "active", "0/${ktFiles.size}")
                for ((i, f) in ktFiles.withIndex()) {
                    uploadFileToGitHub(token, repo, "$ktBase/${f.name}", f.content, branch)
                    setStep("commit", "active", "${i + 1}/${ktFiles.size}")
                    log("${f.name} コミット完了")
                }
                setStep("commit", "done", "${ktFiles.size} files")

                // Step3: ワークフロートリガー
                setStep("trigger", "active", "dispatch...")
                triggerWorkflow(token, repo, branch, appName, appId)
                setStep("trigger", "done", branch)
                log("ビルドトリガー完了", "suc")

                // Step4: ポーリング開始
                setStep("build", "active", "Run取得中...")
                kotlinx.coroutines.delay(4000)
                val runId = getLatestRunId(token, repo)
                if (runId == null) {
                    throw Exception("Runが見つかりません")
                }
                setStep("build", "active", "#$runId")
                log("Run #$runId 監視開始")
                startPolling(runId, token, repo)

            } catch (e: Exception) {
                log("エラー: ${e.message}", "err")
                setStep("build", "fail", "エラー")
                deployBtn.isEnabled = true
                stopPipelineTimer()
            }
        }
    }

    // ─── GitHubファイルアップロード ───
    private suspend fun uploadFileToGitHub(
        token: String, repo: String, path: String, content: String, branch: String
    ) = withContext(Dispatchers.IO) {
        // 既存SHAを取得（更新の場合）
        val sha = try {
            val getUrl = URL("https://api.github.com/repos/$repo/contents/$path")
            val getConn = getUrl.openConnection() as HttpURLConnection
            getConn.setRequestProperty("Authorization", "token $token")
            getConn.setRequestProperty("Accept", "application/vnd.github+json")
            if (getConn.responseCode == 200) {
                val resp = getConn.inputStream.bufferedReader().readText()
                JSONObject(resp).optString("sha").ifEmpty { null }
            } else null
        } catch (e: Exception) { null }

        val encodedContent = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val bodyJson = JSONObject().apply {
            put("message", "chore: update $path")
            put("content", encodedContent)
            if (sha != null) put("sha", sha)
            put("branch", branch)
        }

        val putUrl = URL("https://api.github.com/repos/$repo/contents/$path")
        val putConn = putUrl.openConnection() as HttpURLConnection
        putConn.requestMethod = "PUT"
        putConn.setRequestProperty("Authorization", "token $token")
        putConn.setRequestProperty("Accept", "application/vnd.github+json")
        putConn.setRequestProperty("Content-Type", "application/json")
        putConn.doOutput = true
        OutputStreamWriter(putConn.outputStream).use { it.write(bodyJson.toString()) }
        val code = putConn.responseCode
        if (code != 200 && code != 201) {
            val errText = putConn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw Exception("GitHub API $code: $errText")
        }
    }

    // ─── ワークフロートリガー ───
    private suspend fun triggerWorkflow(
        token: String, repo: String, branch: String, appName: String, appId: String
    ) = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("ref", branch)
            put("inputs", JSONObject().apply {
                put("app_name", appName)
                put("app_id", appId)
            })
        }
        val url = URL("https://api.github.com/repos/$repo/actions/workflows/build-apk.yml/dispatches")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "token $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(bodyJson.toString()) }
        val code = conn.responseCode
        if (code != 204) {
            val errText = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw Exception("Trigger failed $code: $errText")
        }
    }

    // ─── 最新Run ID取得 ───
    private suspend fun getLatestRunId(token: String, repo: String): Long? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/$repo/actions/workflows/build-apk.yml/runs?per_page=1")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "token $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode != 200) return@withContext null
        val resp = conn.inputStream.bufferedReader().readText()
        val runs = JSONObject(resp).getJSONArray("workflow_runs")
        if (runs.length() == 0) return@withContext null
        runs.getJSONObject(0).getLong