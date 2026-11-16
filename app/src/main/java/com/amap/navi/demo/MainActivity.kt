package com.amap.navi.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var aMap: AMap

    private var locationClient: AMapLocationClient? = null
    private lateinit var compassManager: CompassManager

    /** 自定义“我的位置 + 方向箭头”Marker，朝向由传感器驱动 */
    private var myLocationMarker: Marker? = null

    /** 缓存最新定位，供定位按钮回中 */
    private var lastKnownLatLng: LatLng? = null

    /** 最新方位角（已转换为 Marker 的 rotateAngle） */
    private var lastAzimuth: Float = 0f

    private var firstLocationFix = true
    private var locationStarted = false
    private var accuracyWarningShown = false
    private var sensorMissingToastShown = false
    private var lastLocationErrorToastAt = 0L

    private val locationListener = AMapLocationListener { location ->
        if (location == null) return@AMapLocationListener

        if (location.errorCode != 0) {
            val msg = "location error ${location.errorCode}: ${location.errorInfo}"
            Log.w(TAG, msg)
            showLocationErrorThrottled(location)
            return@AMapLocationListener
        }

        // 精度过滤：首点放宽，后续收紧
        val accuracy = location.accuracy
        val maxAcc = if (firstLocationFix) MAX_ACCURACY_FIRST_FIX else MAX_ACCURACY_METERS
        if (accuracy > 0f && accuracy > maxAcc) {
            Log.d(TAG, "skip low-accuracy fix: ${accuracy}m")
            return@AMapLocationListener
        }

        val latLng = LatLng(location.latitude, location.longitude)
        lastKnownLatLng = latLng
        runOnUiThread {
            updateMyLocationMarker(latLng)
            if (firstLocationFix) {
                firstLocationFix = false
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        aMap = mapView.map

        setupMap()
        setupCompass()
        setupLocateButton()

        if (!hasLocationPermission()) {
            requestLocationPermission()
        }
        // 真正开定位放在 onResume，保证前后台切换后能恢复
    }

    private fun setupMap() {
        // 关闭 SDK 自带蓝点，完全用自定义 Marker 控制样式与朝向
        aMap.isMyLocationEnabled = false
        aMap.uiSettings.isMyLocationButtonEnabled = false
        aMap.uiSettings.isCompassEnabled = false
        aMap.uiSettings.isRotateGesturesEnabled = false
        aMap.uiSettings.isTiltGesturesEnabled = false
    }

    private fun setupCompass() {
        compassManager = CompassManager(
            this,
            onAzimuthChanged = { azimuthDegrees ->
                // 传感器方位角 0=正北顺时针；Marker 0=正东逆时针，需转换
                val angle = normalizeMarkerAngle(360f - azimuthDegrees + 180f)
                lastAzimuth = angle
                runOnUiThread {
                    myLocationMarker?.rotateAngle = angle
                }
            },
            onAccuracyChanged = { reliable ->
                runOnUiThread {
                    if (!reliable && !accuracyWarningShown) {
                        accuracyWarningShown = true
                        Toast.makeText(
                            this,
                            "方向传感器不稳定，请远离磁场并画8字校准",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (reliable) {
                        accuracyWarningShown = false
                    }
                }
            }
        )
    }

    private fun setupLocateButton() {
        val btnLocate = findViewById<FloatingActionButton>(R.id.btnLocate)
        btnLocate.setOnClickListener {
            if (!hasLocationPermission()) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.ACCESS_FINE_LOCATION
                    )
                ) {
                    requestLocationPermission()
                } else {
                    showPermissionSettingsDialog()
                }
                return@setOnClickListener
            }

            val latLng = lastKnownLatLng
            if (latLng != null) {
                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
            } else {
                Toast.makeText(this, "还没获取到定位，请稍候", Toast.LENGTH_SHORT).show()
                if (!locationStarted) {
                    startLocation()
                }
            }
        }
    }

    private fun startLocation() {
        if (!hasLocationPermission()) return
        stopLocationInternal()
        try {
            locationClient = AMapLocationClient(applicationContext).apply {
                setLocationListener(locationListener)
                val option = AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isNeedAddress = false
                    isOnceLocation = false
                    interval = 2000L
                    isMockEnable = false
                    isLocationCacheEnable = true
                }
                setLocationOption(option)
                startLocation()
            }
            locationStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "startLocation failed", e)
            Toast.makeText(this, "定位启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            locationStarted = false
        }
    }

    private fun stopLocationInternal() {
        locationClient?.let { client ->
            try {
                client.stopLocation()
                client.onDestroy()
            } catch (e: Exception) {
                Log.w(TAG, "stopLocation error", e)
            }
        }
        locationClient = null
        locationStarted = false
    }

    private fun updateMyLocationMarker(latLng: LatLng) {
        val marker = myLocationMarker
        if (marker == null) {
            myLocationMarker = aMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.marker))
                    .anchor(0.5f, 0.5f)
                    .rotateAngle(lastAzimuth)
                    .setFlat(true)
            )
        } else {
            marker.position = latLng
            marker.rotateAngle = lastAzimuth
        }
    }

    private fun normalizeMarkerAngle(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
    }

    private fun showLocationErrorThrottled(location: AMapLocation) {
        // 鉴权失败只打日志，不弹 Toast（需在控制台配置包名+SHA1）
        val info = location.errorInfo.orEmpty()
        if (info.contains("INVALID_USER_SCODE", ignoreCase = true) ||
            info.contains("auth fail", ignoreCase = true)
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastLocationErrorToastAt < LOCATION_ERROR_TOAST_INTERVAL_MS) return
        lastLocationErrorToastAt = now

        val tip = when (location.errorCode) {
            12 -> "缺少定位权限"
            13 -> "网络异常，无法定位"
            14 -> "定位服务未开启，请打开系统定位"
            else -> "定位失败(${location.errorCode})：${location.errorInfo ?: "未知错误"}"
        }
        runOnUiThread {
            Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQUEST_LOCATION_PERMISSION
        )
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要定位权限")
            .setMessage("定位权限已被关闭，请到系统设置中开启，才能显示当前位置。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION_PERMISSION) return
        if (hasLocationPermission()) {
            startLocation()
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            )
        ) {
            showPermissionSettingsDialog()
        } else {
            Toast.makeText(this, "未授予定位权限，无法显示位置", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (!compassManager.start() && !sensorMissingToastShown) {
            sensorMissingToastShown = true
            Toast.makeText(this, "设备缺少方向传感器，无法显示朝向", Toast.LENGTH_LONG).show()
        }
        if (hasLocationPermission()) {
            startLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        compassManager.stop()
        stopLocationInternal()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationInternal()
        myLocationMarker?.remove()
        myLocationMarker = null
        mapView.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val MAX_ACCURACY_METERS = 80f
        private const val MAX_ACCURACY_FIRST_FIX = 200f
        private const val LOCATION_ERROR_TOAST_INTERVAL_MS = 5000L
    }
}
