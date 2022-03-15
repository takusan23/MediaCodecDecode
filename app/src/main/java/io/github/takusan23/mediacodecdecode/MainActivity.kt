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
    private val MERGE_FILE_NAME = "merged.aac"

    /** MIME_TYPE */
    private val MIME_TYPE = "audio/"

    /** デコードするMIME_TYPE */
    private val DECODE_MIME_TYPE = "audio/mp4a-latm"

    /** 音楽時間 */
    private val DURATION_SEC = 121 // 2分1秒

    /** タイムアウト */
    private val TIMEOUT_US = 10000L

    /** ビットレート */
    private val BIT_RATE = 250000

    /** MediaCodecでもらえるInputBufferのサイズを最大にする */
    private val INPUT_BUFFER_SIZE = 655360

    /**
     * デコードした生データと表示タイムスタンプ
     *
     * 生データ、つまりPCM。AudioTrackで再生できるはず
     * */
    private val decodedByteArrayList = arrayListOf<Pair<ByteArray, Long>>()

    /** 動画ならtrue */
    private val isVideo = false

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
            val fixMediaFormat = mediaFormat?.apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setLong(MediaFormat.KEY_DURATION, DURATION_SEC * 1000L * 1000L) // マイクロ秒
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)

                if (isVideo) {
                    setInteger(MediaFormat.KEY_CAPTURE_RATE, 1)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                }
            }!!

            // MediaMuxer作成
            val mediaMuxer = MediaMuxer(mergedFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // 音声トラック追加
            val audioTrackIndex = mediaMuxer.addTrack(fixMediaFormat)
            mediaMuxer.start()

            // サンプリングレート
            val samplingRate = mediaFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE)!!
            println("サンプリングレート $samplingRate")
            // 生データ再生するやつ
            val audioTrack = createAudioTrack(samplingRate)

            // メタデータ格納用
            val bufferInfo = MediaCodec.BufferInfo()

            // H.264なはず
            // デコード用（aac -> 生データ）MediaCodec
            val decodeMediaCodec = MediaCodec.createDecoderByType(DECODE_MIME_TYPE).apply {
                configure(mediaFormat, null, null, 0)
                start()
            }
            // エンコード用（生データ -> aac）MediaCodec
            val encodeMediaCodec = MediaCodec.createEncoderByType(DECODE_MIME_TYPE).apply {
                configure(fixMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

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
                            showMessage("MediaCodec リリース")
                            // データなくなった場合は終了フラグを立てる
                            decodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            // 開放
                            mediaExtractor!!.release()
                            // 終了
                            break
                        }
                    }
                }

                // デコーダーから生データを受け取る
                val outputBufferId = decodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferId >= 0) {
                    // デコード結果をもらう
                    val outputBuffer = decodeMediaCodec.getOutputBuffer(outputBufferId)!!
                    // 生データをメモリに保持（OOM不可避）
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer[chunk]
                    decodedByteArrayList.add(chunk to 0)
                    // 消したほうがいいらしい
                    outputBuffer.clear()
                    // 返却
                    decodeMediaCodec.releaseOutputBuffer(outputBufferId, false)
                }

            }

            // デコーダー終了
            decodeMediaCodec.stop()
            decodeMediaCodec.release()

            // 経過時間計測用
            var totalBytesRead = 0L
            var mPresentationTime = 0L
            // どこまで生データをエンコードさせたか
            for ((byteArray, presentationTime) in decodedByteArrayList) {

                // audioTrack.write(byteArray, 0, byteArray.size)

                // 生データ
                // エンコーダー部分
                val inputBufferId = encodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferId >= 0) {
                    // デコードした生データをエンコーダーへ渡す
                    val inputBuffer = encodeMediaCodec.getInputBuffer(inputBufferId)!!
                    inputBuffer[byteArray]
                    // エンコーダーへ渡す
                    encodeMediaCodec.queueInputBuffer(inputBufferId, 0, byteArray.size, mPresentationTime, 0)
                    totalBytesRead += byteArray.size
                    mPresentationTime = 1000000L * (totalBytesRead / 2) / samplingRate
                }

                // エンコーダーから圧縮したデータを受け取る
                val outputBufferId = encodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferId >= 0) {
                    // デコード結果をもらう
                    val outputBuffer = encodeMediaCodec.getOutputBuffer(outputBufferId)!!
                    // 書き込む
                    mediaMuxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                    // 返却
                    encodeMediaCodec.releaseOutputBuffer(outputBufferId, false)
                }
            }

            showMessage("エンコード終了")
            // エンコーダー終了
            encodeMediaCodec.stop()
            encodeMediaCodec.release()
            // MediaMuxerも終了
            mediaMuxer.stop()
            mediaMuxer.release()
            // 再生するやつも終了
            audioTrack.release()
        }

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
        decodedByteArrayList.clear()
    }

}