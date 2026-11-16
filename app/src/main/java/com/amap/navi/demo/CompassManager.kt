package com.amap.navi.demo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager

/**
 * 用手机传感器算实时朝向（方位角 0~360，0=正北，顺时针）。
 *
 * 优先 TYPE_ROTATION_VECTOR，否则降级 TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD。
 */
class CompassManager(
    private val context: Context,
    private val onAzimuthChanged: (azimuthDegrees: Float) -> Unit,
    private val onAccuracyChanged: ((reliable: Boolean) -> Unit)? = null
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val useRotationVector = rotationVectorSensor != null

    /** 设备是否具备可用的方向传感器组合 */
    val isAvailable: Boolean =
        useRotationVector || (accelerometer != null && magnetometer != null)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var filteredAzimuth: Float? = null
    private val lowPassAlpha = 0.15f

    private var lastUpdateTime = 0L
    private val minUpdateIntervalMs = 30L

    private var lastReliable: Boolean? = null
    private var started = false

    /**
     * 注册传感器监听。
     * @return false 表示设备没有可用传感器
     */
    fun start(): Boolean {
        if (!isAvailable) return false
        if (started) return true
        started = true

        if (useRotationVector) {
            sensorManager.registerListener(
                this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME
            )
        } else {
            sensorManager.registerListener(
                this, accelerometer, SensorManager.SENSOR_DELAY_GAME
            )
            sensorManager.registerListener(
                this, magnetometer, SensorManager.SENSOR_DELAY_GAME
            )
        }
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
        filteredAzimuth = null
        hasGravity = false
        hasGeomagnetic = false
        lastReliable = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                handleRotationMatrix()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                hasGravity = true
                if (hasGravity && hasGeomagnetic) computeFromGravityAndGeomagnetic()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                hasGeomagnetic = true
                if (hasGravity && hasGeomagnetic) computeFromGravityAndGeomagnetic()
            }
        }
    }

    private fun computeFromGravityAndGeomagnetic() {
        val success = SensorManager.getRotationMatrix(
            rotationMatrix, null, gravity, geomagnetic
        )
        if (success) handleRotationMatrix()
    }

    private fun handleRotationMatrix() {
        val rotation = currentDisplayRotation()
        val (axisX, axisY) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
        SensorManager.getOrientation(remappedMatrix, orientation)

        var azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        azimuthDeg = normalizeAngle(azimuthDeg)

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < minUpdateIntervalMs) return
        lastUpdateTime = now

        val smoothed = lowPassFilterAngle(filteredAzimuth, azimuthDeg, lowPassAlpha)
        filteredAzimuth = smoothed
        onAzimuthChanged(smoothed)
    }

    private fun currentDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.rotation
        }
    }

    private fun lowPassFilterAngle(prev: Float?, next: Float, alpha: Float): Float {
        if (prev == null) return next
        var diff = next - prev
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return normalizeAngle(prev + alpha * diff)
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_MAGNETIC_FIELD &&
            sensor?.type != Sensor.TYPE_ROTATION_VECTOR
        ) {
            return
        }
        val reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        if (lastReliable == reliable) return
        lastReliable = reliable
        onAccuracyChanged?.invoke(reliable)
    }
}
