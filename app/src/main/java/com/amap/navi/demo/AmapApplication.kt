package com.amap.navi.demo

import android.app.Application
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer

class AmapApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 必须在使用地图/定位 SDK 任何接口之前调用，且两个方法都要传 true
        // 建议这里配合你自己 App 的隐私政策弹窗逻辑：
        // 真实项目一般是"用户点了同意"之后才调这两个方法，
        // 这个 Demo 简化处理，启动就直接同意。
        setupAmapPrivacy()
    }

    private fun setupAmapPrivacy() {
        // 地图 SDK 隐私合规
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)

        // 定位 SDK 隐私合规，跟地图 SDK 是两套独立的开关，必须分别设置
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
    }
}
