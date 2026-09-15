package com.unbound.rpg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.unbound.rpg.ui.UnboundApp
import com.unbound.rpg.ui.theme.UnboundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as UnboundApplication).container
        setContent {
            UnboundTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UnboundApp(container)
                }
            }
        }
    }
}
