package com.smartcalc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.smartcalc.app.ui.AppRoot
import com.smartcalc.app.ui.math.MathRenderer
import com.smartcalc.app.ui.theme.SmartCalcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Шрифты формул грузятся долго — пусть это случится до первого ответа, а не во время него.
        lifecycleScope.launch(Dispatchers.Default) { MathRenderer.warmUp() }
        setContent {
            SmartCalcTheme {
                AppRoot()
            }
        }
    }
}
