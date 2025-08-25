package com.example.brightnessdisplayy

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var textView: TextView
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else finish() // 権限がないと動かないので終了
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.processedView)
        textView = findViewById(R.id.textView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 解析用 UseCase（プレビュー不要）
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(cameraExecutor, Optical_Flow { dx, dy, bmp ->
                runOnUiThread {
                    imageView.setImageBitmap(bmp)
                    textView.text = "$dx, $dy"
                }
            })

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, analysis)
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 光学式マウスのように、前フレームと現在フレームの中心 50x50 の Y（輝度）だけを比較して
     * もっとも一致するシフト量 (dx, dy) を SAD（Sum of Absolute Differences）で推定します。
     *
     * - dx, dy は「画面表示の向き」に合わせて回転した結果を listener に渡します。
     * - 画像はグレースケール（Y）だけで Bitmap を作って listener に渡します（回転適用）。
     */
    private class Optical_Flow(
        private val listener: (dx: Int, dy: Int, bmp: Bitmap) -> Unit
    ) : ImageAnalysis.Analyzer {

        private val cropSize: Int = 50
        private val searchRadius: Int = 5 // 探索窓（+- このピクセル範囲で探索）

        // 前回フレームの中心 50x50 の Y（輝度）を保持
        private var prevY: ByteArray? = null
        private var prevSide: Int = 0

        override fun analyze(image: ImageProxy) {
            try {
                val yPlane = image.planes[0]
                val yBuffer: ByteBuffer = yPlane.buffer
                val yRowStride = yPlane.rowStride
                val yPixelStride = yPlane.pixelStride // 通常 1 だが一般化して処理
                require(yPixelStride == 1) { "Unsupported yPixelStride: $yPixelStride" }

                val width = image.width
                val height = image.height

                val side = min(min(cropSize, width), height)
                val left = (width - side) / 2
                val top = (height - side) / 2

                // 中心 50x50 の Y を取り出しつつ、表示用の ARGB も作る
                val cropY = ByteArray(side * side) // Y（輝度）の生値（Byte: -128..127）を格納
                val pixels = IntArray(side * side) // 表示用 ARGB（不透明グレー）

                val yDup = yBuffer.duplicate()
                yDup.clear() // position=0, limit=capacity に

                var idx = 0
                val rowBytes = ByteArray(side)
                for (row in 0 until side) {
                    val base = (top + row) * yRowStride + left
                    yDup.position(base)
                    yDup.get(rowBytes, 0, side)

                    for (col in 0 until side) {
                        val b = rowBytes[col]              // Byte
                        cropY[idx] = b                      // Y をそのまま保存
                        val v = b.toInt() and 0xFF          // 0..255 に正規化
                        pixels[idx] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                        idx++
                    }
                }

                // ここで前回フレームと比較して (dx, dy) を推定（センサー座標系）
                var dxSensor = 0
                var dySensor = 0
                val prevLocal = prevY
                if (prevLocal != null && prevSide == side) {
                    var bestScore = Long.MAX_VALUE
                    var bestDx = 0
                    var bestDy = 0

                    // 探索（SAD 最小を探す）
                    for (dy in -searchRadius..searchRadius) {
                        // 有効な y 範囲を算出（はみ出しを除外）
                        val yStart = if (dy > 0) dy else 0
                        val yEnd = if (dy < 0) side + dy else side
                        if (yStart >= yEnd) continue

                        for (dx in -searchRadius..searchRadius) {
                            val xStart = if (dx > 0) dx else 0
                            val xEnd = if (dx < 0) side + dx else side
                            if (xStart >= xEnd) continue

                            var sad = 0L
                            // 早期打ち切り（現在のベストより悪化したら次へ）
                            loop@ for (y in yStart until yEnd) {
                                val iCurRow = y * side
                                val iPrevRow = (y - dy) * side
                                for (x in xStart until xEnd) {
                                    val cur = cropY[iCurRow + x].toInt() and 0xFF
                                    val prv = prevLocal[iPrevRow + (x - dx)].toInt() and 0xFF
                                    sad += abs(cur - prv)
                                    if (sad >= bestScore) {
                                        break@loop
                                    }
                                }
                            }

                            if (sad < bestScore) {
                                bestScore = sad
                                bestDx = dx
                                bestDy = dy
                            }
                        }
                    }

                    dxSensor = bestDx
                    dySensor = bestDy
                }

                // 表示用 Bitmap を作成
                val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                bmp.setPixels(pixels, 0, side, 0, 0, side, side)

                // 表示の回転を取得（CW: 時計回り）
                val rotation = image.imageInfo.rotationDegrees
                val m = Matrix().apply { if (rotation != 0) postRotate(rotation.toFloat()) }
                val rotated = if (rotation != 0)
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                else
                    bmp

                if (rotated !== bmp) bmp.recycle()

                // センサー座標の (dx,dy) を「画面表示の向き」に合わせて回転
                val (dxDisplay, dyDisplay) = rotateDeltaByDegrees(dxSensor, dySensor, rotation)

                // コールバック
                listener(dxDisplay, dyDisplay, rotated)

                // 現在のフレームを次回用に保持
                prevY = cropY
                prevSide = side

            } catch (t: Throwable) {
                // 必要ならログ
                // Log.e("Optical_Flow", "analyze error", t)
            } finally {
                // 必ず解放
                image.close()
            }
        }

        /**
         * ベクトル (dx, dy) を rotation 度（時計回り）だけ回転させる。
         * rotation は 0, 90, 180, 270 のいずれか。
         */
        private fun rotateDeltaByDegrees(dx: Int, dy: Int, rotation: Int): Pair<Int, Int> {
            return when ((rotation % 360 + 360) % 360) {
                0 -> dx to dy
                90 -> dy to -dx
                180 -> -dx to -dy
                270 -> -dy to dx
                else -> dx to dy // 想定外はそのまま
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

}