package com.ndx.rustforge.ide.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.ndx.rustforge.ide.viewmodel.SetupState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(state: SetupState, onStart: () -> Unit, onContinue: () -> Unit) {
    LaunchedEffect(Unit) { onStart() }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when {
            state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Não foi possível concluir", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(state.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onStart) { Text("Tentar novamente") }
            }
            !state.finished -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(24.dp))
                AnimatedContent(
                    targetState = state.label,
                    transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(300)) },
                    label = "setup-step"
                ) { label ->
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
            else -> SuccessContent(version = state.version, onContinue = onContinue)
        }
    }
}

@Composable
private fun SuccessContent(version: String?, onContinue: () -> Unit) {
    val offset = remember { Animatable(160f) }
    val bannerAlpha = remember { Animatable(0f) }
    val versionAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { offset.animateTo(0f, tween(1000, easing = FastOutSlowInEasing)) }
        bannerAlpha.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
        delay(1200)
        bannerAlpha.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
        versionAlpha.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Rust Instalado com Sucesso",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .offset(y = offset.value.dp)
                .alpha(bannerAlpha.value)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            version.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.alpha(versionAlpha.value)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onContinue, modifier = Modifier.alpha(versionAlpha.value)) {
            Text("Começar")
        }
    }
}
