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
    private val FOLDER_NAME = "bakkure"

    /** ファイル名 */
    private val MERGE_FILE_NAME = "merged.aac"

    /** MIME_TYPE */
    private val MIME_TYPE = "audio/"

    /** デコードするMIME_TYPE */
    private val DECODE_MIME_TYPE = "audio/mp4a-latm"

    /** タイムアウト */
    private val TIMEOUT_US = 10000L

    /** ビットレート */
    private val BIT_RATE = 192_000

    /** サンプリングレート */
    private val SAMPLING_RATE = 44100

    /** チャンネル数 */
    private val CHANNEL_COUNT = 2

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

        // 音声だけの生データ
        val rawAudioFile = File(getExternalFilesDir(null), "raw_audio").apply {
            if (!exists()) {
                delete()
            }
            createNewFile()
        }

        // 書き込むやつ
        val bufferedInputStream = rawAudioFile.outputStream().buffered()

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
            val fixMediaFormat = MediaFormat.createAudioFormat(DECODE_MIME_TYPE, SAMPLING_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            // MediaMuxer作成
            val mediaMuxer = MediaMuxer(mergedFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // 音声トラック追加
            val audioTrackIndex = mediaMuxer.addTrack(mediaFormat!!)
            mediaMuxer.start()

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

            // エンコーダー、デコーダーの種類を出す
            val message = """
                エンコーダー：${encodeMediaCodec.name}
                デコーダー：${decodeMediaCodec.name}
            """.trimIndent()
            showMessage(message)

            /**
             *  --- 複数ファイルを全てデコードして生データにする（PCM） ---
             * */
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

                // デコーダーから生データを受け取る
                val outputBufferId = decodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferId >= 0) {
                    // デコード結果をもらう
                    val outputBuffer = decodeMediaCodec.getOutputBuffer(outputBufferId)!!
                    // 生データを一時的に保存する
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer[chunk]
                    bufferedInputStream.write(chunk)
                    // decodedByteArrayList.add(chunk to 0)
                    // 消したほうがいいらしい
                    outputBuffer.clear()
                    // 返却
                    decodeMediaCodec.releaseOutputBuffer(outputBufferId, false)
                }
            }

            // デコーダー終了
            decodeMediaCodec.stop()
            decodeMediaCodec.release()
            bufferedInputStream.close()
            showMessage("デコード完了")

            /**
             *  --- 生データをAACでエンコードする ---
             * */
            val rawDataInputStream = rawAudioFile.inputStream().buffered()
            // 読み出し済みの位置と時間
            var totalBytesRead = 0
            var mPresentationTime = 0L
            // 生データからエンコード
            while (true) {
                val inputBufferId = encodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferId >= 0) {
                    // デコードした生データをエンコーダーへ渡す
                    val inputBuffer = encodeMediaCodec.getInputBuffer(inputBufferId)!!
                    val buffer = ByteArray(inputBuffer.capacity())
                    val size = rawDataInputStream.read(buffer)
                    // エンコーダーへ渡す
                    if (size > 0) {
                        // 書き込む。書き込んだデータは[onOutputBufferAvailable]で受け取れる
                        inputBuffer.put(buffer, 0, size)
                        encodeMediaCodec.queueInputBuffer(inputBufferId, 0, size, mPresentationTime, 0)
                        // ここはAOSPパクった。経過時間を計算してる
                        totalBytesRead += size
                        println("読み出しバイト数 $totalBytesRead")
                        // これ totalBytesRead / 4 であっってんの？
                        // 何故か時間が2倍してある,,,は？
                        mPresentationTime = 1000000L * (totalBytesRead / (CHANNEL_COUNT * 2)) / SAMPLING_RATE
                    } else {
                        encodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }
                }
                // デコーダーから生データを受け取る
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

            // エンコーダー終了
            encodeMediaCodec.stop()
            encodeMediaCodec.release()

            // MediaMuxerも終了
            // MediaMuxer#stopで終了する場合、大体MediaFormatのパラメータ不足です。
            // MediaExtractorで出てきたFormatを入れると直ると思います。
            mediaMuxer.stop()
            mediaMuxer.release()
            rawDataInputStream.close()

            // 一時ファイルの削除
            rawAudioFile.delete()
            showMessage("エンコード終了")
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

    override fun onDestroy() {
        super.onDestroy()
        decodedByteArrayList.clear()
    }

}
