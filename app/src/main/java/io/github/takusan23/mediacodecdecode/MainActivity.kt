package io.github.takusan23.mediacodecdecode

import android.annotation.SuppressLint
import android.media.*
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File


/**
 * /sdcard/Android/data/io.github.takusan23.mediacodecdecode/files/video
 *
 * に入れた動画を再生します。
 *
 * 映像トラックだけです。
 * */
class MainActivity : AppCompatActivity() {

    private val surfaceView by lazy { findViewById<SurfaceView>(R.id.surface_view) }
    private val FOLDER_NAME = "split"

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val videoFolder = File(getExternalFilesDir(null), FOLDER_NAME).apply {
            if (!exists()) {
                mkdir()
            }
        }

        // 数字を見つけ正規表現
        val numberRegex = "(\\d+)".toRegex()
        // にある動画ファイルを配列にしてイテレータ
        // 連続再生いらない場合は一個だけ入れればいいと思います
        val videoItemIterator = videoFolder.listFiles()
            // ?.filter { it.extension == "ts" } // これ動画ファイル以外が入ってくる場合はここで見切りをつける
            ?.toList()
            ?.sortedBy { numberRegex.find(it.name)?.groupValues?.get(0)?.toIntOrNull() ?: 0 } // 数字の若い順にする
            ?.listIterator() ?: return

        if (!videoItemIterator.hasNext()) {
            showMessage("ファイルがありません")
            return
        }

        // 現在の動画を抽出してるやつ
        var mediaExtractor: MediaExtractor? = null
        // メタデータ
        var mediaFormat: MediaFormat? = null

        // MediaExtractorで動画ファイルを読み出す
        fun extractVideoFile(path: String) {
            // 動画の情報を読み出す
            val (_mediaExtractor, index, format) = extractMedia(path, "audio/") ?: return
            mediaExtractor = _mediaExtractor
            mediaFormat = format
            // トラックを選択
            mediaExtractor?.selectTrack(index)
        }

        // 最初のファイルの情報をゲット
        extractVideoFile(videoItemIterator.next().path)

        // サンプリングレート
        val samplingRate = mediaFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE)!!
        // 必須サイズを計算する
        val bufferSize = AudioTrack.getMinBufferSize(
            samplingRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        // 音声再生するやつ
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().apply {
                setUsage(AudioAttributes.USAGE_MEDIA)
            }.build())
            .setAudioFormat(AudioFormat.Builder().apply {
                setSampleRate(samplingRate)
                setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            }.build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // 再生開始
        track.play()

        // H.264なはず
        val mediaCodec = MediaCodec.createDecoderByType("audio/mp4a-latm")
        // 非同期モード
        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                val inputBuffer = mediaCodec.getInputBuffer(index) ?: return
                val size = mediaExtractor?.readSampleData(inputBuffer, 0) ?: return
                if (size > 0) {
                    // デコーダーへ流す
                    mediaCodec.queueInputBuffer(index, 0, size, mediaExtractor!!.sampleTime, 0)
                    mediaExtractor!!.advance()
                } else {
                    // 閉じる
                    if (videoItemIterator.hasNext()) {
                        // 次データへ
                        val file = videoItemIterator.next()
                        showMessage("MediaExtractor 次データへ ${file.name}")
                        // 多分いる
                        mediaCodec.queueInputBuffer(index, 0, 0, 0, 0)
                        // 動画の情報を読み出す
                        mediaExtractor!!.release()
                        extractVideoFile(file.path)
                    } else {
                        showMessage("MediaCodec リリース")
                        // データなくなった際
                        mediaCodec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        // 開放
                        mediaExtractor!!.release()
                        mediaCodec.stop()
                        mediaCodec.release()
                    }
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // AudioTrackへ流す
                val outputBuffer = mediaCodec.getOutputBuffer(index) ?: return

                val outData = ByteArray(info.size)
                outputBuffer.get(outData)
                track.write(outData, 0, outData.size)

                codec.releaseOutputBuffer(index, false)
                // mediaCodec.releaseOutputBuffer(index, true)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                e.printStackTrace()
                mediaCodec.stop()
                mediaCodec.release()
                mediaExtractor?.release()
                showMessage(e.message ?: "問題が発生しました")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // フォーマットが変わった？
            }
        })

        // Surfaceが利用可能になったらMediaCodec起動
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // MediaCodecの設定をする
                val format = mediaFormat ?: return
                mediaCodec.configure(format, /*holder.surface*/null, null, 0)
                mediaCodec.start()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {

            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {

            }
        })
    }

    /**
     * [MediaExtractor]で抽出する
     *
     * @param videoPath 動画パス
     * @param mimeType MIMEタイプ
     * @return MediaExtractor コンテナの映像トラックのIndex MediaFormat の順番で入ってる
     * */
    private fun extractMedia(videoPath: String, mimeType: String): Triple<MediaExtractor, Int, MediaFormat>? {
        println(videoPath)
        val mediaExtractor = MediaExtractor().apply { setDataSource(videoPath) }
        // 映像トラックとインデックス番号のPairを作って返す
        val (index, track) = (0 until mediaExtractor.trackCount)
            .map { index -> index to mediaExtractor.getTrackFormat(index) }
            .firstOrNull { (_, track) -> track.getString(MediaFormat.KEY_MIME)?.startsWith(mimeType) == true } ?: return null
        return Triple(mediaExtractor, index, track)
    }

    private fun showMessage(message: String) {
        println(message)
        runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
    }

}