package com.negocio.editorvideos

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private val clips = mutableListOf<Clip>()
    private var logoBitmap: Bitmap? = null
    private var musicUri: Uri? = null
    private var lastExport: File? = null
    private var exporter: VideoExporter? = null
    private var exporting = false

    private lateinit var prefs: SharedPreferences

    private lateinit var ivPreview: ImageView
    private lateinit var clipsContainer: LinearLayout
    private lateinit var tvTotal: TextView
    private lateinit var rgFormat: RadioGroup
    private lateinit var etText: TextInputEditText
    private lateinit var spTextPos: Spinner
    private lateinit var ivLogo: ImageView
    private lateinit var spCorner: Spinner
    private lateinit var tvMusic: TextView
    private lateinit var slMusicVol: Slider
    private lateinit var slOrigVol: Slider
    private lateinit var btnExport: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvStatus: TextView
    private lateinit var resultRow: View

    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) addClips(uris)
        }

    private val pickLogo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) setLogo(uri)
        }

    private val pickMusic =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                musicUri = uri
                tvMusic.text = "♪ " + displayName(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Evita que el contenido quede debajo de la barra de estado / navegación / teclado
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        prefs = getSharedPreferences("ajustes", MODE_PRIVATE)

        ivPreview = findViewById(R.id.ivPreview)
        clipsContainer = findViewById(R.id.clipsContainer)
        tvTotal = findViewById(R.id.tvTotal)
        rgFormat = findViewById(R.id.rgFormat)
        etText = findViewById(R.id.etText)
        spTextPos = findViewById(R.id.spTextPos)
        ivLogo = findViewById(R.id.ivLogo)
        spCorner = findViewById(R.id.spCorner)
        tvMusic = findViewById(R.id.tvMusic)
        slMusicVol = findViewById(R.id.slMusicVol)
        slOrigVol = findViewById(R.id.slOrigVol)
        btnExport = findViewById(R.id.btnExport)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)
        resultRow = findViewById(R.id.resultRow)

        spTextPos.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Arriba", "Centro", "Abajo")
        )
        spCorner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Arriba izquierda", "Arriba derecha", "Abajo izquierda", "Abajo derecha")
        )

        restoreSettings()

        findViewById<MaterialButton>(R.id.btnAddClips).setOnClickListener {
            pickVideos.launch(arrayOf("video/*"))
        }
        findViewById<MaterialButton>(R.id.btnLogo).setOnClickListener { pickLogo.launch("image/*") }
        findViewById<MaterialButton>(R.id.btnRemoveLogo).setOnClickListener {
            logoBitmap = null
            File(filesDir, LOGO_FILE).delete()
            ivLogo.setImageDrawable(null)
            updatePreview()
        }
        findViewById<MaterialButton>(R.id.btnMusic).setOnClickListener {
            pickMusic.launch(arrayOf("audio/*"))
        }
        findViewById<MaterialButton>(R.id.btnRemoveMusic).setOnClickListener {
            musicUri = null
            tvMusic.text = "Sin música"
        }
        btnExport.setOnClickListener { if (exporting) cancelExport() else startExport() }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener { shareResult() }
        findViewById<MaterialButton>(R.id.btnPlay).setOnClickListener { playResult() }

        rgFormat.setOnCheckedChangeListener { _, _ -> updatePreview() }
        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        })
        val onSelect = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updatePreview()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spTextPos.onItemSelectedListener = onSelect
        spCorner.onItemSelectedListener = onSelect

        if (savedInstanceState == null) handleSharedIntent(intent)
        renderClips()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    override fun onDestroy() {
        exporter?.cancel()
        super.onDestroy()
    }

    // ---------- Videos ----------

    private fun handleSharedIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { addClips(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { addClips(it) }
        }
    }

    private fun addClips(uris: List<Uri>) {
        tvStatus.text = "Cargando videos…"
        Thread {
            val loaded = uris.mapNotNull { uri -> loadClip(uri) }
            runOnUiThread {
                clips.addAll(loaded)
                tvStatus.text = if (loaded.size < uris.size) "Algunos archivos no se pudieron leer." else ""
                renderClips()
            }
        }.start()
    }

    private fun loadClip(uri: Uri): Clip? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            if (duration < 300) return null
            val frame = try {
                retriever.getFrameAtTime(min(1_000_000L, duration * 500L))
            } catch (e: Exception) {
                null
            }
            Clip(uri, displayName(uri), duration, 0L, duration, frame?.let { scaleDown(it, 480) })
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun renderClips() {
        clipsContainer.removeAllViews()
        clips.forEachIndexed { index, clip ->
            val row = layoutInflater.inflate(R.layout.item_clip, clipsContainer, false)
            val tvName = row.findViewById<TextView>(R.id.tvName)
            val tvRange = row.findViewById<TextView>(R.id.tvRange)
            val slider = row.findViewById<RangeSlider>(R.id.rangeSlider)

            tvName.text = "${index + 1}. ${clip.name}"
            clip.thumb?.let { row.findViewById<ImageView>(R.id.ivThumb).setImageBitmap(it) }

            val maxSec = max(clip.durationMs, 1000L) / 1000f
            slider.valueFrom = 0f
            slider.valueTo = maxSec
            slider.values = listOf(
                (clip.startMs / 1000f).coerceIn(0f, maxSec),
                (clip.endMs / 1000f).coerceIn(0f, maxSec)
            )
            slider.setLabelFormatter { value -> formatTime((value * 1000).toLong()) }
            tvRange.text = rangeText(clip)
            slider.addOnChangeListener { s, _, _ ->
                val v = s.values
                clip.startMs = (v[0] * 1000).toLong()
                clip.endMs = min((v[1] * 1000).toLong(), clip.durationMs)
                tvRange.text = rangeText(clip)
                updateTotal()
            }

            row.findViewById<Button>(R.id.btnUp).setOnClickListener {
                if (index > 0) {
                    clips.add(index - 1, clips.removeAt(index))
                    renderClips()
                }
            }
            row.findViewById<Button>(R.id.btnDown).setOnClickListener {
                if (index < clips.size - 1) {
                    clips.add(index + 1, clips.removeAt(index))
                    renderClips()
                }
            }
            row.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                clips.removeAt(index)
                renderClips()
            }
            clipsContainer.addView(row)
        }
        updateTotal()
        updatePreview()
    }

    private fun rangeText(clip: Clip) =
        "Desde ${formatTime(clip.startMs)} hasta ${formatTime(clip.endMs)} " +
            "(${formatTime(clip.endMs - clip.startMs)})"

    private fun updateTotal() {
        val total = clips.sumOf { max(0L, it.endMs - it.startMs) }
        tvTotal.text = "Duración total: ${formatTime(total)}"
    }

    // ---------- Logo ----------

    private fun setLogo(uri: Uri) {
        try {
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bmp == null) {
                toast("No se pudo abrir la imagen")
                return
            }
            val small = scaleDown(bmp, 800)
            FileOutputStream(File(filesDir, LOGO_FILE)).use {
                small.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            logoBitmap = small
            ivLogo.setImageBitmap(small)
            updatePreview()
        } catch (e: Exception) {
            toast("No se pudo abrir la imagen")
        }
    }

    // ---------- Vista previa ----------

    private fun updatePreview() {
        if (!::ivPreview.isInitialized) return
        val (w, h) = outputSize()
        val s = 640f / max(w, h)
        val pw = (w * s).toInt()
        val ph = (h * s).toInt()
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)

        val thumb = clips.firstOrNull()?.thumb
        if (thumb != null) {
            val scale = min(pw / thumb.width.toFloat(), ph / thumb.height.toFloat())
            val tw = thumb.width * scale
            val th = thumb.height * scale
            val left = (pw - tw) / 2f
            val top = (ph - th) / 2f
            canvas.drawBitmap(thumb, null, RectF(left, top, left + tw, top + th), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = pw * 0.05f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Agrega videos para ver la vista previa", pw / 2f, ph / 2f, p)
        }

        OverlayRenderer.render(pw, ph, currentText(), textPos(), logoBitmap, corner())?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }
        ivPreview.setImageBitmap(bmp)
    }

    // ---------- Exportar ----------

    private fun startExport() {
        if (clips.isEmpty()) {
            toast("Primero agrega al menos un video")
            return
        }
        val badClip = clips.indexOfFirst { it.endMs - it.startMs < 300 }
        if (badClip >= 0) {
            toast("El clip ${badClip + 1} quedó demasiado corto")
            return
        }
        saveSettings()

        val (w, h) = outputSize()
        val overlay = OverlayRenderer.render(w, h, currentText(), textPos(), logoBitmap, corner())

        val dir = File(cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "video_negocio_$stamp.mp4")

        setExporting(true)
        val exp = VideoExporter(this)
        exporter = exp
        exp.start(
            clips = clips.toList(),
            width = w,
            height = h,
            overlay = overlay,
            musicUri = musicUri,
            musicVolume = slMusicVol.value / 100f,
            originalVolume = slOrigVol.value / 100f,
            output = out,
            cb = object : VideoExporter.Callback {
                override fun onProgress(percent: Int) {
                    progress.setProgressCompat(percent, true)
                    tvStatus.text = "Creando video… $percent%"
                }

                override fun onDone(file: File) {
                    setExporting(false)
                    lastExport = file
                    val saved = saveToGallery(file)
                    tvStatus.text = if (saved) "¡Listo! Guardado en la galería (carpeta Movies/EditorNegocio)."
                    else "¡Listo! Usa Compartir para enviarlo o guardarlo."
                    resultRow.visibility = View.VISIBLE
                }

                override fun onError(message: String) {
                    setExporting(false)
                    tvStatus.text = "No se pudo crear el video.\n$message"
                }
            }
        )
    }

    private fun cancelExport() {
        exporter?.cancel()
        setExporting(false)
        tvStatus.text = "Cancelado"
    }

    private fun setExporting(on: Boolean) {
        exporting = on
        btnExport.text = if (on) "Cancelar" else "Crear video"
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            progress.isIndeterminate = false
            progress.progress = 0
            resultRow.visibility = View.GONE
            tvStatus.text = "Preparando…"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exporter = null
        }
    }

    private fun saveToGallery(file: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EditorNegocio")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return false
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun resultUri(): Uri? {
        val file = lastExport ?: return null
        if (!file.exists()) return null
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun shareResult() {
        val uri = resultUri() ?: return toast("Primero crea un video")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartir video"))
    }

    private fun playResult() {
        val uri = resultUri() ?: return toast("Primero crea un video")
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast("No hay reproductor de video instalado")
        }
    }

    // ---------- Ajustes ----------

    private fun outputSize(): Pair<Int, Int> = when (rgFormat.checkedRadioButtonId) {
        R.id.rbSquare -> 1080 to 1080
        R.id.rbHorizontal -> 1920 to 1080
        else -> 1080 to 1920
    }

    private fun currentText() = etText.text?.toString() ?: ""

    private fun textPos() = OverlayRenderer.TextPos.entries[spTextPos.selectedItemPosition.coerceIn(0, 2)]

    private fun corner() = OverlayRenderer.Corner.entries[spCorner.selectedItemPosition.coerceIn(0, 3)]

    private fun saveSettings() {
        val format = when (rgFormat.checkedRadioButtonId) {
            R.id.rbSquare -> 1
            R.id.rbHorizontal -> 2
            else -> 0
        }
        prefs.edit()
            .putString("texto", currentText())
            .putInt("posTexto", spTextPos.selectedItemPosition)
            .putInt("esquina", spCorner.selectedItemPosition)
            .putInt("formato", format)
            .putFloat("volMusica", slMusicVol.value)
            .putFloat("volOriginal", slOrigVol.value)
            .apply()
    }

    private fun restoreSettings() {
        etText.setText(prefs.getString("texto", ""))
        spTextPos.setSelection(prefs.getInt("posTexto", 2))
        spCorner.setSelection(prefs.getInt("esquina", 1))
        rgFormat.check(
            when (prefs.getInt("formato", 0)) {
                1 -> R.id.rbSquare
                2 -> R.id.rbHorizontal
                else -> R.id.rbVertical
            }
        )
        slMusicVol.value = prefs.getFloat("volMusica", 40f).coerceIn(0f, 100f)
        slOrigVol.value = prefs.getFloat("volOriginal", 100f).coerceIn(0f, 100f)

        val logoFile = File(filesDir, LOGO_FILE)
        if (logoFile.exists()) {
            logoBitmap = BitmapFactory.decodeFile(logoFile.absolutePath)
            ivLogo.setImageBitmap(logoBitmap)
        }
    }

    // ---------- Utilidades ----------

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "archivo"
    }

    private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val biggest = max(bmp.width, bmp.height)
        if (biggest <= maxSide) return bmp
        val s = maxSide.toFloat() / biggest
        return Bitmap.createScaledBitmap(
            bmp, max(1, (bmp.width * s).toInt()), max(1, (bmp.height * s).toInt()), true
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSec = max(0L, ms) / 1000
        val tenths = (max(0L, ms) % 1000) / 100
        return "%d:%02d.%d".format(totalSec / 60, totalSec % 60, tenths)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LOGO_FILE = "logo.png"
    }
}
