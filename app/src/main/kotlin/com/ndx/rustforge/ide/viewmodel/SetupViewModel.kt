package com.ndx.rustforge.ide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ndx.rustforge.ide.data.Bootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SetupState(
    val label: String = "Configurando Linux",
    val version: String? = null,
    val error: String? = null,
    val finished: Boolean = false
)

class SetupViewModel(private val app: Application) : AndroidViewModel(app) {
    val alreadyReady: Boolean = Bootstrap.isReady(app)
    private val log = File(app.cacheDir, "bootstrap-build.log")
    private val _state = MutableStateFlow(SetupState(finished = alreadyReady))
    val state: StateFlow<SetupState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (_state.value.finished || job?.isActive == true) return
        _state.update { SetupState() }
        log.writeText("RustForge AArch64 bootstrap installation\n")
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val version = install()
                _state.update { it.copy(label = "Instalação concluída", finished = true, version = version) }
            } catch (e: Exception) {
                log.appendText("\nFALHA: ${e.stackTraceToString()}\n")
                _state.update { it.copy(error = e.message ?: "Erro desconhecido") }
            }
        }
    }

    private fun install(): String {
        if (!Bootstrap.isReady(app)) {
            step("Extraindo ambiente Linux AArch64")
            logLine("Extraindo bootstrap personalizado...")
            Bootstrap.extract(app)
        }

        step("Validando Bash, Rust e Cargo")
        logLine("Executando testes reais no ambiente Android...")
        val version = Bootstrap.validate(app)
        logLine("Validação concluída: $version")
        step("Finalizando instalação")
        Bootstrap.markReady(app)
        logLine("Marcador .rustforge_ready criado após todas as verificações.")
        return version
    }

    private fun step(label: String) {
        _state.update { it.copy(label = label, error = null) }
    }

    private fun logLine(line: String) {
        log.appendText(line + "\n")
    }
}
