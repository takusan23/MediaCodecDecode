package io.github.takusan23.mediacodecdecode

import android.annotation.SuppressLint
import android.media.*
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs


/**
 * /sdcard/Android/data/io.github.takusan23.mediacodecdecode/files/video
 *
 * にいれた動画の音声だけをまとめる。
 *
 * 音声トラックだけです
 * */
class MainActivity : AppCompatActivity() {

    private val surfaceView by lazy { findViewById<SurfaceView>(R.id.surface_view) }

    /** 動画ファイルがあるフォルダ名 */
    private val FOLDER_NAME = "split"

    /** ファイル名 */
    private val MERGE_FILE_NAME = "merged.mp4"

    /** MIME_TYPE */
    private val MIME_TYPE = "video/"

    /** デコードするMIME_TYPE */
    private val DECODE_MIME_TYPE = "video/avc" // MediaFormat.MIMETYPE_VIDEO_AVC

    /** タイムアウト */
    private val TIMEOUT_US = 10000L

    /** ビットレート */
    private val BIT_RATE = 1000 * 1024

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

        // 別スレッド起動
        // whileループでActivity止まるので
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
            // データを取り出してMediaFormatを作る。なぜかコピーしたのを突っ込んだらコケた
            // TODO エミュレータだとコピーしてパラメータを足してエンコーダーに突っ込むとコケるかも
            val fixMediaFormat = /*MediaFormat.createVideoFormat(DECODE_MIME_TYPE, width, height)*/mediaFormat?.apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)
                // setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setString(MediaFormat.KEY_MIME, DECODE_MIME_TYPE)
                setInteger(MediaFormat.KEY_BIT_RATE, /*BIT_RATE*/1920 * 1080)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }!!

            // MediaMuxer作成
            val mediaMuxer = MediaMuxer(mergedFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // 映像トラック追加
            val videoTrackIndex = mediaMuxer.addTrack(fixMediaFormat)
            mediaMuxer.start()

            // メタデータ格納用
            val encoderBufferInfo = MediaCodec.BufferInfo()

            // エンコード用（生データ -> H.264）MediaCodec
            val encodeMediaCodec = MediaCodec.createEncoderByType(DECODE_MIME_TYPE).apply {
                configure(fixMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            // H.264なはず
            // デコード用（H.264 -> 生データ）MediaCodec
            val decodeMediaCodec = MediaCodec.createDecoderByType(DECODE_MIME_TYPE).apply {
                configure(fixMediaFormat, null, null, 0)
                start()
            }


            // エンコーダー、デコーダーの種類を出す
            val message = """
                 エンコーダー：${encodeMediaCodec.name}
                 デコーダー：${decodeMediaCodec.name}
             """.trimIndent()
            showMessage(message)

            // 読み出し済みの位置と時間
            var totalBytesRead = 0
            var presentationTime = 0L

            /**
             *  --- 複数ファイルを全てデコードする ---
             * */
            thread {
                while (true) {
                    // デコーダー部分
                    val inputBufferId = decodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferId >= 0) {
                        // Extractorからデータを読みだす
                        val inputBuffer = decodeMediaCodec.getInputBuffer(inputBufferId)!!
                        val size = mediaExtractor!!.readSampleData(inputBuffer, 0)
                        if (size > 0) {
                            // デコーダーへ流す
                            decodeMediaCodec.queueInputBuffer(inputBufferId, 0, size, mediaExtractor!!.sampleTime, 0)
                            mediaExtractor!!.advance()
                        } else {
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
                        // こいつをエンコーダーに投げる
                        val chunk = decodeMediaCodec.getOutputBuffer(outputBufferId)!!.let { outputBuffer ->
                            val chunk = ByteArray(outputBuffer.capacity())
                            outputBuffer[chunk]
                            // 消したほうがいいらしい
                            outputBuffer.clear()
                            // ByteArrayを返す
                            chunk
                        }
                        // エンコーダーへ書き込む前にリリースする
                        decodeMediaCodec.releaseOutputBuffer(outputBufferId, false)

                        /**
                         *  --- デコードした生データをエンコーダーに入れる ---
                         * */
                        var readByteSize = 0
                        // 分割する
                        while (true) {
                            val inputBufferId = encodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
                            if (inputBufferId >= 0) {
                                // デコードした生データをエンコーダーへ渡す
                                val inputBuffer = encodeMediaCodec.getInputBuffer(inputBufferId)!!
                                val writeByteArray = ByteArray(inputBuffer.capacity())
                                val size = writeByteArray.size
                                // データが有る限り読み出す
                                if (readByteSize < chunk.size) {
                                    inputBuffer.put(chunk, readByteSize, size)
                                    readByteSize += size
                                    // 書き込む。書き込んだデータは[onOutputBufferAvailable]で受け取れる
                                    inputBuffer.put(writeByteArray, 0, size)
                                    encodeMediaCodec.queueInputBuffer(inputBufferId, 0, size, presentationTime, 0)
                                } else {
                                    // 終了フラグを立てる
                                    encodeMediaCodec.queueInputBuffer(inputBufferId, 0, size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    break
                                }
                                // 時間を足す
                                presentationTime += abs(presentationTime - encoderBufferInfo.presentationTimeUs)
                            }
                            // デコーダーから生データを受け取る
                            val outputBufferId = encodeMediaCodec.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US)
                            if (outputBufferId >= 0) {
                                // デコード結果をもらう
                                val outputBuffer = encodeMediaCodec.getOutputBuffer(outputBufferId)!!
                                // 書き込む
                                mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, encoderBufferInfo)
                                // 返却
                                encodeMediaCodec.releaseOutputBuffer(outputBufferId, false)
                            }
                        }
                    }
                }

                // デコーダー終了
                decodeMediaCodec.stop()
                decodeMediaCodec.release()
                showMessage("デコード完了")

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

    private fun waitSurface(onCreate: (Surface) -> Unit) {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onCreate(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {

            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {

            }
        })
    }

    /** [AudioTrack]をつくる */
    @SuppressLint("NewApi")
    private fun createAudioTrack(samplingRate: Int): AudioTrack {
        // 必須サイズを計算する
        val bufferSize = AudioTrack.getMinBufferSize(
            samplingRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 音声再生するやつ
        val audioTrack = AudioTrack.Builder()
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
        audioTrack.play()
        return audioTrack
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

    override fun onDestroy() {
        super.onDestroy()
    }

}
