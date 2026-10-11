package com.smartcalc.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.smartcalc.app.data.request.active
import com.smartcalc.app.ui.AppRoot
import com.smartcalc.app.ui.math.MathRenderer
import com.smartcalc.app.ui.theme.SmartCalcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Шрифты формул грузятся долго — пусть это случится до первого ответа, а не во время него.
        lifecycleScope.launch(Dispatchers.Default) { MathRenderer.warmUp() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                (application as SmartCalcApplication).generation.state.collect { request ->
                    if (request?.active == true && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        val preferences = getSharedPreferences("notification_permission", MODE_PRIVATE)
                        if (!preferences.getBoolean("asked", false)) {
                            preferences.edit().putBoolean("asked", true).apply()
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }
        }
        setContent {
            SmartCalcTheme {
                AppRoot()
            }
        }
    }
}
