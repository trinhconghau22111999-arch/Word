package com.wordclone.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PdfPrint
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val REQ_FILE = 1001

    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            saveBytes(name, mime, Base64.decode(base64, Base64.DEFAULT))
        }

        @JavascriptInterface
        fun savePdf(name: String, size: String, orientation: String) {
            runOnUiThread { exportPdf(name, size, orientation) }
        }

        @JavascriptInterface
        fun shareFile(name: String, mime: String, base64: String, target: String) {
            runOnUiThread { doShare(name, mime, Base64.decode(base64, Base64.DEFAULT), target) }
        }

        @JavascriptInterface
        fun setClipboard(text: String, html: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = if (html.isNotEmpty()) ClipData.newHtmlText("Word", text, html) else ClipData.newPlainText("Word", text)
                cm.setPrimaryClip(clip)
            }
        }

        @JavascriptInterface
        fun getClipboard(): String {
            val out = AtomicReference("{}")
            val latch = CountDownLatch(1)
            runOnUiThread {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    if (item != null) {
                        val o = org.json.JSONObject()
                        o.put("text", item.coerceToText(this@MainActivity)?.toString() ?: "")
                        o.put("html", item.htmlText ?: "")
                        out.set(o.toString())
                    }
                } catch (_: Exception) {}
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
            return out.get()
        }

        @JavascriptInterface
        fun speak(text: String, lang: String) {
            runOnUiThread { startSpeaking(text, lang) }
        }

        @JavascriptInterface
        fun stopSpeak() {
            runOnUiThread { tts?.stop() }
        }

        @JavascriptInterface
        fun goHome() {
            runOnUiThread {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        }

        @JavascriptInterface
        fun print() {
            runOnUiThread {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                pm.print("Document", web.createPrintDocumentAdapter("Document"), PrintAttributes.Builder().build())
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread {
                val u = Uri.parse(url)
                if (u.scheme in listOf("http", "https", "mailto", "tel")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                } else {
                    Toast.makeText(this@MainActivity, "Không mở được liên kết này", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun notifyTtsDone() {
        runOnUiThread { web.evaluateJavascript("window.onTtsDone && window.onTtsDone()", null) }
    }

    private fun startSpeaking(text: String, lang: String) {
        val doSpeak = {
            val engine = tts!!
            val loc = Locale.forLanguageTag(lang)
            val r = engine.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Thiết bị chưa có giọng đọc cho ngôn ngữ này, dùng giọng mặc định", Toast.LENGTH_LONG).show()
            }
            val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
            val chunks = ArrayList<String>()
            var cur = StringBuilder()
            for (piece in text.split(Regex("(?<=[.!?\\n])"))) {
                if (cur.length + piece.length > max && cur.isNotEmpty()) { chunks.add(cur.toString()); cur = StringBuilder() }
                if (piece.length > max) piece.chunked(max).forEach { chunks.add(it) } else cur.append(piece)
            }
            if (cur.isNotBlank()) chunks.add(cur.toString())
            if (chunks.isEmpty()) notifyTtsDone()
            engine.stop()
            chunks.forEachIndexed { i, c ->
                engine.speak(c, TextToSpeech.QUEUE_ADD, null, if (i == chunks.size - 1) "last" else "part$i")
            }
        }
        if (tts != null && ttsReady) { doSpeak(); return }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "last") notifyTtsDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { notifyTtsDone() }
                    override fun onStop(id: String?, interrupted: Boolean) { notifyTtsDone() }
                })
                runOnUiThread { doSpeak() }
            } else {
                Toast.makeText(this, "Không khởi tạo được giọng đọc", Toast.LENGTH_LONG).show()
                notifyTtsDone()
            }
        }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray) {
        try {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (Build.VERSION.SDK_INT >= 29) {
                val rel = Environment.DIRECTORY_DOWNLOADS + "/Word/"
                val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Lưu đè nếu file cùng tên (do app tạo) đã có, tránh sinh "Document1 (1).docx"
                var existing: Uri? = null
                contentResolver.query(
                    col, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(safe, rel), null
                )?.use { c -> if (c.moveToFirst()) existing = ContentUris.withAppendedId(col, c.getLong(0)) }
                var done = false
                existing?.let { u ->
                    try {
                        contentResolver.openOutputStream(u, "wt")!!.use { it.write(bytes) }
                        done = true
                    } catch (_: Exception) {}
                }
                if (!done) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Word")
                    }
                    val uri = contentResolver.insert(col, v)!!
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                }
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Word").also { it.mkdirs() }
                File(dir, safe).writeBytes(bytes)
            }
            runOnUiThread {
                val where = if (Build.VERSION.SDK_INT >= 29) "Download/Word" else "Android/data/$packageName/files/Download/Word"
                Toast.makeText(this, "Đã lưu $safe vào $where", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Lỗi lưu file: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun exportPdf(name: String, size: String, orientation: String) {
        try {
            var media = when (size) {
                "Letter" -> PrintAttributes.MediaSize.NA_LETTER
                "Legal" -> PrintAttributes.MediaSize.NA_LEGAL
                else -> PrintAttributes.MediaSize.ISO_A4
            }
            media = if (orientation == "landscape") media.asLandscape() else media.asPortrait()
            val attrs = PrintAttributes.Builder()
                .setMediaSize(media)
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            val tmp = File(cacheDir, "export.pdf")
            val adapter = web.createPrintDocumentAdapter("Document")
            PdfPrint(attrs).print(adapter, tmp) { ok, err ->
                runOnUiThread {
                    if (ok) {
                        saveBytes(name, "application/pdf", tmp.readBytes())
                        tmp.delete()
                    } else {
                        Toast.makeText(this, "Không tạo được PDF: ${err ?: "lỗi không rõ"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Không tạo được PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doShare(name: String, mime: String, bytes: ByteArray, target: String) {
        try {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dir = File(cacheDir, "share").also { it.mkdirs() }
            val f = File(dir, safe)
            f.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val (pkg, label) = when (target) {
                "gmail" -> "com.google.android.gm" to "Gmail"
                "drive" -> "com.google.android.apps.docs" to "Google Drive"
                else -> "com.zing.zalo" to "Zalo"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, safe)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent(intent).setPackage(pkg))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Chưa cài $label — chọn ứng dụng khác để chia sẻ", Toast.LENGTH_LONG).show()
                startActivity(Intent.createChooser(intent, "Chia sẻ qua"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi chia sẻ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "file") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }.show()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Hủy") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                val input = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
                AlertDialog.Builder(this@MainActivity).setMessage(message).setView(input)
                    .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("Hủy") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = cb
                val accept = params.acceptTypes.joinToString(",")
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (accept.contains("image/") && !accept.contains(".")) "image/*" else "*/*"
                }
                return try {
                    startActivityForResult(Intent.createChooser(intent, "Chọn file"), REQ_FILE); true
                } catch (e: Exception) {
                    filePathCallback = null; false
                }
            }
        }
        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            filePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            filePathCallback = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){var g=document.getElementById('genericDialog');if(g){g.remove();return 1;}" +
            "var b=document.getElementById('backstage');if(b&&!b.hidden){closeBackstage();return 1;}return 0;})()"
        ) { r -> if (r != "1") finish() }
    }
}
