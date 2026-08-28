
package com.isro.navaidr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

data class EdgeNavigationState(
    var latitude: Double,
    var longitude: Double,
    var heading: Double,
    var speedKmh: Float,
    var isDeadReckoningActive: Boolean
)

class EdgeNavigationEngine(private val modelPath: String) {

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    private val bufferChannels = 6
    private val windowSize = 50
    private val sensorBuffer = Array(bufferChannels) { FloatArray(windowSize) }
    private var sampleCount = 0
    private val dt = 0.005 // Supports high-frequency ingestion (~200Hz processing rate)

    init {
        initEngine()
    }

    private fun initEngine() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            ortSession = ortEnvironment?.createSession(modelPath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Modular ingestion method supporting external high-grade IMU payloads or file streams
    fun ingestImuSample(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float, currentHeading: Double): EdgeNavigationState? {
        if (sampleCount < windowSize) {
            sensorBuffer[0][sampleCount] = ax
            sensorBuffer[1][sampleCount] = ay
            sensorBuffer[2][sampleCount] = az
            sensorBuffer[3][sampleCount] = gx
            sensorBuffer[4][sampleCount] = gy
            sensorBuffer[5][sampleCount] = gz
            sampleCount++
        }

        if (sampleCount >= windowSize) {
            sampleCount = 0
            return runInference(sensorBuffer, currentHeading)
        }
        return null
    }

    fun runInference(buffer: Array<FloatArray>, heading: Double): EdgeNavigationState? {
        val session = ortSession ?: return null
        val env = ortEnvironment ?: return null

        try {
            val flatData = FloatArray(bufferChannels * windowSize)
            var index = 0
            for (c in 0 until bufferChannels) {
                for (w in 0 until windowSize) {
                    flatData[index++] = buffer[c][w]
                }
            }

            val tensorShape = longArrayOf(1, bufferChannels.toLong(), windowSize.toLong())
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatData), tensorShape)
            val inputName = session.inputNames.iterator().next()
            val output = session.run(mapOf(inputName to inputTensor))

            val outputTensor = output[0] as OnnxTensor
            val velocityArray = outputTensor.floatBuffer.array()

            // Calculate variance to detect stationary state
            var totalVariance = 0.0f
            for (c in 0 until 3) {
                val mean = buffer[c].average().toFloat()
                totalVariance += buffer[c].map { (it - mean) * (it - mean) }.sum() / windowSize
            }

            val rawPredictedSpeed = if (velocityArray.isNotEmpty()) velocityArray[0] else 0.0f
            val predictedSpeed = if (totalVariance < 0.02f || rawPredictedSpeed < 0.5f) {
                0.0f
            } else {
                rawPredictedSpeed
            }

            inputTensor.close()
            output.close()

            return EdgeNavigationState(
                latitude = 0.0,
                longitude = 0.0,
                heading = heading,
                speedKmh = predictedSpeed * 3.6f,
                isDeadReckoningActive = true
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun release() {
        ortSession?.close()
        ortEnvironment?.close()
    }
}