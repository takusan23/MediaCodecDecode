package io.github.takusan23.mediacodecdecode

import android.annotation.SuppressLint
import android.media.*
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread


/**
 * /sdcard/Android/data/io.github.takusan23.mediacodecdecode/files/video
 *
 * にいれた動画の音声だけをまとめる。
 *
 * 音声トラックだけです
 * */
class MainActivity : AppCompatActivity() {

    /** 動画ファイルがあるフォルダ名 */
    private val FOLDER_NAME = "split"

    /** ファイル名 */
    private val MERGE_FILE_NAME = "merged.mp4"

    /** MIME_TYPE */
    private val MIME_TYPE = "video/"

    /** デコードするMIME_TYPE */
    private val DECODE_MIME_TYPE = "video/avc" // MediaFormat.MIMETYPE_VIDEO_AVC

    /** エンコードするMIME_TYPE */
    private val ENCODE_MIME_TYPE = DECODE_MIME_TYPE

    /** 書き出し先コンテナフォーマット */
    private val OUTPUT_CONATINER_FORMAT = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4

    /** タイムアウト */
    private val TIMEOUT_US = 10000L

    /** ビットレート */
    private val BIT_RATE = 192_000 // 192kbps

    /** MediaCodecでもらえるInputBufferのサイズ */
    private val INPUT_BUFFER_SIZE = 655360

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 複数ある音声ファイルの保存先
        val videoFolder = File(getExternalFilesDir(null), FOLDER_NAME).apply {
            if (!exists()) {
                mkdir()
            }
        }

        // 結合するファイル
        val mergedFile = File(getExternalFilesDir(null), MERGE_FILE_NAME).apply {
            if (!exists()) {
                delete()
            }
            createNewFile()
        }

        // 数字を見つける正規表現
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

        // コルーチンでも良くない
        thread {

            // 現在の動画を抽出してるやつ
            var mediaExtractor: MediaExtractor? = null
            // メタデータ
            var mediaFormat: MediaFormat? = null

            // MediaExtractorで動画ファイルを読み出す
            fun extractVideoFile(path: String) {
                // 動画の情報を読み出す
                val (_mediaExtractor, index, format) = extractMedia(path, MIME_TYPE) ?: return
                mediaExtractor = _mediaExtractor
                mediaFormat = format
                // トラックを選択
                mediaExtractor?.selectTrack(index)
            }

            // 最初のファイルの情報をゲット
            extractVideoFile(videoItemIterator.next().path)

            // エンコーダーへ渡すMediaFormat
            println(mediaFormat)
            val height = mediaFormat?.getInteger(MediaFormat.KEY_HEIGHT) ?: 720
            val width = mediaFormat?.getInteger(MediaFormat.KEY_WIDTH) ?: 1280
            // 多分Extractorそのまま突っ込むとコケるので参考にしながら作る
            val decoderMediaFormat = MediaFormat.createVideoFormat(DECODE_MIME_TYPE, width, height).apply {
                // setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            // エンコーダー用MediaFormat
            val encoderMediaFormat = MediaFormat.createVideoFormat(ENCODE_MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            // MediaMuxer作成
            val mediaMuxer = MediaMuxer(mergedFile.path, OUTPUT_CONATINER_FORMAT)
            // 映像トラック追加
            val videoTrackIndex = mediaMuxer.addTrack(mediaFormat!!) // H.264の場合、なんか必須データが無いとかでこれはExtractorの方を入れる
            mediaMuxer.start()

            // エンコード用（生データ -> H.264）MediaCodec
            val encodeMediaCodec = MediaCodec.createEncoderByType(ENCODE_MIME_TYPE).apply {
                configure(encoderMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            // エンコーダーのSurfaceを取得
            // デコーダーの出力Surfaceの項目にこれを指定して、エンコーダーに映像データが行くようにする
            val inputSurface = encodeMediaCodec.createInputSurface()
            // Surface取得後Startする
            encodeMediaCodec.start()

            // H.264なはず
            // デコード用（H.264 -> 生データ）MediaCodec
            val decodeMediaCodec = MediaCodec.createDecoderByType(DECODE_MIME_TYPE).apply {
                configure(decoderMediaFormat, inputSurface, null, 0)
                start()
            }

            // エンコーダー、デコーダーの種類を出す
            val message = """
                 エンコーダー：${encodeMediaCodec.name}
                 デコーダー：${decodeMediaCodec.name}
             """.trimIndent()
            showMessage(message)

            // デコードが終わったフラグ
            var isEOLDecode = false

            /**
             * --- Surfaceに流れてきたデータをエンコードする ---
             * */
            thread {
                val outputBufferInfo = MediaCodec.BufferInfo()
                // デコード結果をもらう
                // デコーダーが生きている間のみ
                while (!isEOLDecode) {
                    val outputBufferId = encodeMediaCodec.dequeueOutputBuffer(outputBufferInfo, TIMEOUT_US)
                    if (outputBufferId >= 0) {
                        val outputBuffer = encodeMediaCodec.getOutputBuffer(outputBufferId)!!
                        // 書き込む
                        mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, outputBufferInfo)
                        // 返却
                        encodeMediaCodec.releaseOutputBuffer(outputBufferId, false)
                    }
                }
            }

            /**
             *  --- 複数ファイルを全てデコードする ---
             * */
            thread {
                var totalPresentationTime = 0L
                var prevPresentationTime = 0L
                // メタデータ格納用
                val encoderBufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    // デコーダー部分
                    val inputBufferId = decodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferId >= 0) {
                        // Extractorからデータを読みだす
                        val inputBuffer = decodeMediaCodec.getInputBuffer(inputBufferId)!!
                        val size = mediaExtractor!!.readSampleData(inputBuffer, 0)
                        if (size > 0) {
                            // デコーダーへ流す
                            // 今までの動画の分の再生位置を足しておく
                            // フレーム番号 * 1000000 / FPS でも出せるらしい
                            decodeMediaCodec.queueInputBuffer(inputBufferId, 0, size, mediaExtractor!!.sampleTime + totalPresentationTime, 0)
                            mediaExtractor!!.advance()
                            // 一個前の動画の動画サイズを控えておく
                            // else で extractor.sampleTime すると既に-1にっているので
                            if (mediaExtractor!!.sampleTime != -1L) {
                                prevPresentationTime = mediaExtractor!!.sampleTime
                            }
                        } else {
                            totalPresentationTime += prevPresentationTime
                            // データがないので次データへ
                            if (videoItemIterator.hasNext()) {
                                // 次データへ
                                val file = videoItemIterator.next()
                                showMessage("MediaExtractor 次データへ ${file.name}")
                                // 多分いる
                                decodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, 0)
                                // 動画の情報を読み出す
                                mediaExtractor!!.release()
                                extractVideoFile(file.path)
                            } else {
                                // データなくなった場合は終了フラグを立てる
                                decodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                // 開放
                                mediaExtractor!!.release()
                                // 終了
                                break
                            }
                        }
                    }
                    // デコードした内容をエンコーダーへ移す
                    val outputBufferId = decodeMediaCodec.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US)
                    // ネストがやばくなってきた,,,
                    if (outputBufferId >= 0) {
                        // デコード結果をもらう
                        decodeMediaCodec.releaseOutputBuffer(outputBufferId, true)
                    }
                }

                // デコーダー終了
                isEOLDecode = true
                decodeMediaCodec.stop()
                decodeMediaCodec.release()

                // エンコーダー終了
                encodeMediaCodec.stop()
                encodeMediaCodec.release()

                // MediaMuxerも終了
                mediaMuxer.stop()
                mediaMuxer.release()
                showMessage("エンコード終了")
            }
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
