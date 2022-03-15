package io.github.takusan23.mediacodecdecode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * /sdcard/Android/data/io.github.takusan23.mediacodecdecode/files/video
 *
 * に入れた動画を再生します。
 *
 * 映像トラックだけです。
 * */
class MainActivity : AppCompatActivity() {

    private val surfaceView by lazy { findViewById<SurfaceView>(R.id.surface_view) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val videoFolder = File(getExternalFilesDir(null), "video").apply {
            if (!exists()) {
                mkdir()
            }
        }

        // にある動画ファイルを配列にしてイテレータ
        // 連続再生いらない場合は一個だけ入れればいいと思います
        val videoItemIterator = videoFolder.listFiles()
            // ?.filter { it.extension == "ts" } // これ動画ファイル以外が入ってくる場合はここで見切りをつける
            ?.toList()?.listIterator() ?: return

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
            val (_mediaExtractor, index, format) = extractMedia(path, "video/") ?: return
            mediaExtractor = _mediaExtractor
            mediaFormat = format
            // トラックを選択
            mediaExtractor?.selectTrack(index)
        }

        extractVideoFile(videoItemIterator.next().path)

        // スレッドを止めないと行けないので（メインスレッドを止めるわけには行かない）別スレッドで動かす
        // これ非同期モードの意味なくね？
        // スレッドと止めないと再生速度が早すぎるため
        thread {

            val startMs = System.currentTimeMillis()
            // H.264なはず
            val mediaCodec = MediaCodec.createDecoderByType("video/avc")
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
                    // Surfaceへレンダリング
                    // だけど早すぎる、ので無理やり制御する
                    // ありざいす！！！１
                    // https://github.com/cedricfung/MediaCodecDemo/blob/master/src/io/vec/demo/mediacodec/DecodeActivity.java#L128
                    while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
                        try {
                            Thread.sleep(10)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                            break
                        }
                    }
                    mediaCodec.releaseOutputBuffer(index, true)
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
                    mediaCodec.configure(format, holder.surface, null, 0)
                    mediaCodec.start()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {

                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {

                }
            })
        }
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