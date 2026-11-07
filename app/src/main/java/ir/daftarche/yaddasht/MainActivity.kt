package ir.daftarche.yaddasht

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var notesContainer: LinearLayout
    private var speechRecognizer: SpeechRecognizer? = null
    private val prefs by lazy { getSharedPreferences("notes_store", MODE_PRIVATE) }
    private val notes = mutableListOf<Note>()
    private var editingId: Long? = null

    data class Note(val id: Long, val text: String, val time: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(20, 72, 110)
        window.navigationBarColor = Color.rgb(20, 72, 110)
        loadNotes()
        buildScreen()
        renderNotes()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 12)
            setBackgroundColor(Color.rgb(247, 249, 252))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val title = TextView(this).apply {
            text = "دفترچه یادداشت صوتی"
            textSize = 23f
            setTextColor(Color.rgb(20, 72, 110))
            gravity = Gravity.CENTER
            setPadding(4, 10, 4, 16)
        }
        root.addView(title, matchWrap())

        status = TextView(this).apply {
            text = "برای شروع، صحبت کنید یا متن را بنویسید."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 10)
        }
        root.addView(status, matchWrap())

        editor = EditText(this).apply {
            hint = "متن یادداشت..."
            textSize = 17f
            gravity = Gravity.TOP or Gravity.RIGHT
            minLines = 5
            setPadding(14, 12, 14, 12)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        actions.addView(actionButton("🎙 شروع گفتار") { startSpeech() }, weighted())
        actions.addView(actionButton("ذخیره یادداشت") { saveNote() }, weighted())
        root.addView(actions, matchWrap())

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        secondRow.addView(actionButton("یادداشت جدید") {
            editingId = null
            editor.setText("")
            status.text = "یادداشت جدید"
        }, weighted())
        secondRow.addView(actionButton("پاک‌کردن متن") { editor.setText("") }, weighted())
        root.addView(secondRow, matchWrap())

        val heading = TextView(this).apply {
            text = "یادداشت‌های ذخیره‌شده"
            textSize = 18f
            setTextColor(Color.rgb(20, 72, 110))
            setPadding(2, 18, 2, 8)
        }
        root.addView(heading, matchWrap())

        val scroll = ScrollView(this)
        notesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(notesContainer)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weighted() = LinearLayout.LayoutParams(0, 52, 1f).apply {
        setMargins(4, 7, 4, 7)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        setOnClickListener { action() }
    }

    private fun startSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "سرویس تشخیص گفتار روی گوشی فعال نیست."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "گوش می‌دهم... صحبت کنید." }
                override fun onBeginningOfSpeech() { status.text = "در حال شنیدن صدای شما..." }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "در حال تبدیل گفتار به متن..." }
                override fun onError(error: Int) {
                    status.text = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "خطا در میکروفون."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مجوز میکروفون داده نشده است."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "خطای اینترنت؛ اتصال را بررسی کنید."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "گفتاری تشخیص داده نشد؛ دوباره تلاش کنید."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "سرویس مشغول است؛ چند لحظه بعد تلاش کنید."
                        else -> "تشخیص گفتار ناموفق بود. دوباره تلاش کنید."
                    }
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        val old = editor.text.toString().trim()
                        editor.setText(if (old.isEmpty()) spoken else "$old $spoken")
                        editor.setSelection(editor.text.length)
                        status.text = "متن تبدیل شد؛ می‌توانید ویرایش و ذخیره کنید."
                    } else status.text = "متنی دریافت نشد."
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) status.text = "در حال تشخیص: $partial"
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "صحبت کنید")
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            status.text = "امکان شروع تشخیص گفتار وجود ندارد."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startSpeech()
            else status.text = "برای تبدیل گفتار، اجازه دسترسی به میکروفون لازم است."
        }
    }

    private fun saveNote() {
        val text = editor.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "ابتدا متن یادداشت را وارد کنید.", Toast.LENGTH_SHORT).show()
            return
        }
        val id = editingId ?: System.currentTimeMillis()
        val time = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale("fa", "IR")).format(Date())
        val index = notes.indexOfFirst { it.id == id }
        val note = Note(id, text, time)
        if (index >= 0) notes[index] = note else notes.add(0, note)
        editingId = id
        persistNotes()
        renderNotes()
        status.text = "یادداشت ذخیره شد."
        Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
    }

    private fun loadNotes() {
        notes.clear()
        val array = try { JSONArray(prefs.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            notes.add(Note(obj.optLong("id"), obj.optString("text"), obj.optString("time")))
        }
    }

    private fun persistNotes() {
        val array = JSONArray()
        notes.forEach {
            array.put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.time))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun renderNotes() {
        notesContainer.removeAllViews()
        if (notes.isEmpty()) {
            notesContainer.addView(TextView(this).apply {
                text = "هنوز یادداشتی ذخیره نشده است."
                textSize = 14f
                setPadding(8, 12, 8, 12)
            })
            return
        }
        notes.forEach { note ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(Color.WHITE)
            }
            card.addView(TextView(this).apply {
                text = note.text
                textSize = 16f
                setTextColor(Color.BLACK)
            }, matchWrap())
            card.addView(TextView(this).apply {
                text = note.time
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 5, 0, 5)
            }, matchWrap())
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            buttons.addView(Button(this).apply {
                text = "ویرایش"
                setOnClickListener {
                    editingId = note.id
                    editor.setText(note.text)
                    editor.setSelection(editor.text.length)
                    status.text = "در حال ویرایش یادداشت"
                }
            }, weighted())
            buttons.addView(Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    notes.removeAll { it.id == note.id }
                    if (editingId == note.id) {
                        editingId = null
                        editor.setText("")
                    }
                    persistNotes()
                    renderNotes()
                    status.text = "یادداشت حذف شد."
                }
            }, weighted())
            card.addView(buttons, matchWrap())
            val outer = LinearLayout(this)
            outer.setPadding(0, 0, 0, 10)
            outer.addView(card, matchWrap())
            notesContainer.addView(outer, matchWrap())
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}
