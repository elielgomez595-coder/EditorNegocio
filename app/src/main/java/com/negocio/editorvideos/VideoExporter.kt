@file:Suppress("UnsafeOptInUsageError")

package com.negocio.editorvideos

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File

/** Un video agregado por el usuario, con el tramo que se va a conservar. */
class Clip(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    var startMs: Long,
    var endMs: Long,
    var thumb: Bitmap? = null
)

/**
 * Une, corta, pone texto/logo y música usando Media3 Transformer
 * (usa el codificador de video del propio teléfono, por eso es rápido).
 */
class VideoExporter(private val context: Context) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onDone(file: File)
        fun onError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var callback: Callback? = null

    private val poller = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                callback?.onProgress(progressHolder.progress)
            }
            handler.postDelayed(this, 300)
        }
    }

    fun start(
        clips: List<Clip>,
        width: Int,
        height: Int,
        overlay: Bitmap?,
        musicUri: Uri?,
        musicVolume: Float,
        originalVolume: Float,
        output: File,
        cb: Callback
    ) {
        callback = cb

        val videoItems = clips.map { clip ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.startMs)
                .setEndPositionMs(
                    if (clip.endMs >= clip.durationMs - 50) C.TIME_END_OF_SOURCE else clip.endMs
                )
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(clipping)
                .build()

            // Efectos nuevos por cada clip (los efectos guardan estado interno)
            val videoEffects = mutableListOf<Effect>(
                Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
            )
            if (overlay != null) {
                videoEffects.add(
                    OverlayEffect(
                        ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlay))
                    )
                )
            }
            val audioEffects: List<AudioProcessor> =
                if (originalVolume < 0.995f) listOf(volumeProcessor(originalVolume)) else emptyList()

            EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(audioEffects, videoEffects))
                .build()
        }

        val sequences = mutableListOf(EditedMediaItemSequence(videoItems, false))

        if (musicUri != null) {
            val music = EditedMediaItem.Builder(MediaItem.fromUri(musicUri))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf<AudioProcessor>(volumeProcessor(musicVolume)), emptyList()))
                .build()
            // La música se repite si es más corta que el video y se corta al final
            sequences.add(EditedMediaItemSequence(listOf(music), true))
        }

        val compositionBuilder = Composition.Builder(sequences)
        if (Build.VERSION.SDK_INT >= 29) {
            // Videos HDR de cámaras modernas se convierten a color normal (más compatible)
            compositionBuilder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        if (musicUri == null) {
            // Si algún clip no tiene sonido, se rellena con silencio en vez de fallar
            compositionBuilder.experimentalSetForceAudioTrack(true)
        }
        val composition = compositionBuilder.build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    cb.onDone(output)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finish()
                    output.delete()
                    cb.onError("${exportException.errorCodeName}: ${exportException.message ?: ""}")
                }
            })
            .build()

        transformer = t
        t.start(composition, output.absolutePath)
        handler.post(poller)
    }

    fun cancel() {
        transformer?.cancel()
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(poller)
        transformer = null
    }

    private fun volumeProcessor(volume: Float): AudioProcessor {
        val processor = ChannelMixingAudioProcessor()
        for (channels in 1..6) {
            processor.putChannelMixingMatrix(
                ChannelMixingMatrix.create(channels, channels).scaleBy(volume)
            )
        }
        return processor
    }
}
