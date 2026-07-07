package com.example.vehiclestatedemo

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.vehiclestatedemo.theme.MyApplicationTheme

private const val TAG = "VehicleStateDemo"

/**
 * 車両の UX Restriction を監視し、NO_VIDEO が有効かどうかで
 * 「走っています」/「止まっています」を切り替えるだけの AAOS サンプル。
 */
class MainActivity : ComponentActivity() {

    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var moving by mutableStateOf(false)

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        applyRestrictions(r)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        car = Car.createCar(this)
        uxManager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager

        setContent {
            MyApplicationTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (moving) "走っています" else "止まっています",
                        style = MaterialTheme.typography.displayLarge
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val m = uxManager ?: return
        m.registerListener(listener)
        applyRestrictions(m.currentCarUxRestrictions) // 初期表示を反映
    }

    override fun onStop() {
        super.onStop()
        uxManager?.unregisterListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        car?.disconnect()
        car = null
    }

    private fun applyRestrictions(r: CarUxRestrictions?) {
        val active = r?.activeRestrictions ?: 0
        moving = active and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO != 0
        Log.d(TAG, "activeRestrictions=0x${active.toString(16)}, moving=$moving")
    }
}
