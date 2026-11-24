package com.ndx.rustforge.ide.viewmodel

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import com.ndx.rustforge.ide.data.Bootstrap
import com.ndx.rustforge.ide.data.Project
import com.ndx.rustforge.ide.data.ProjectRepository
import com.ndx.rustforge.ide.data.Template
import com.ndx.rustforge.ide.shell.ShellRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class IdeDiagnostic(val file: String, val line: Int, val column: Int, val severity: String, val message: String)
data class CodeSymbol(val name: String, val kind: String, val line: Int)
data class IdeState(
    val projects: List<Project> = emptyList(), val openProject: Project? = null, val cwd: File? = null,
    val currentFile: File? = null, val editor: TextFieldValue = TextFieldValue(""), val dirty: Boolean = false,
    val terminal: List<String> = emptyList(), val running: Boolean = false, val treeVersion: Int = 0,
    val message: String? = null, val diagnostics: List<IdeDiagnostic> = emptyList(),
    val symbols: List<CodeSymbol> = emptyList(), val buildProgress: String = "",
    val completions: List<String> = emptyList(), val analyzerInstalled: Boolean = false
)

class IdeViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = ProjectRepository(app)
    private val shell = ShellRunner()
    private val _state = MutableStateFlow(IdeState())
    val state: StateFlow<IdeState> = _state.asStateFlow()
    private val rustWords = listOf("as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "String", "Vec", "Option", "Result", "Some", "None", "Ok", "Err", "println!", "format!", "derive")

    init { refreshProjects() }
    fun refreshProjects() = _state.update { it.copy(projects = repo.list()) }
    fun createProject(name: String, template: Template) {
        runCatching { repo.create(name, template) }.onSuccess { openProject(it) }.onFailure { notify(it.message ?: "Falha ao criar projeto") }
        refreshProjects()
    }
    fun deleteProject(project: Project) { repo.delete(project); refreshProjects() }
    fun openProject(project: Project) {
        _state.update { it.copy(openProject = project, cwd = project.dir, currentFile = null, editor = TextFieldValue(""), dirty = false, treeVersion = it.treeVersion + 1, diagnostics = emptyList()) }
        openFile(File(project.dir, "src/main.rs"))
    }
    fun closeProject() { saveCurrent(); _state.update { it.copy(openProject = null, cwd = null, currentFile = null, editor = TextFieldValue(""), dirty = false, diagnostics = emptyList(), symbols = emptyList()) }; refreshProjects() }
    fun openFile(file: File) {
        if (!saveCurrent()) return
        val root = _state.value.openProject?.dir ?: return
        val safe = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (!safe.path.startsWith(root.canonicalPath + File.separator) && safe != root.canonicalFile) { notify("Caminho fora do projeto bloqueado"); return }
        if (!safe.isFile) return
        val text = runCatching { safe.readText() }.getOrElse { notify("Não foi possível abrir ${safe.name}"); return }
        _state.update { it.copy(currentFile = safe, editor = TextFieldValue(text), dirty = false, symbols = parseSymbols(text), completions = emptyList()) }
    }
    fun newFile(relativePath: String) {
        val root = _state.value.openProject?.dir ?: return
        runCatching {
            require(relativePath.isNotBlank() && !relativePath.startsWith("/")) { "Informe um caminho relativo" }
            val file = File(root, relativePath).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator)) { "Caminho fora do projeto" }
            file.parentFile?.mkdirs(); if (!file.exists()) file.createNewFile(); openFile(file)
        }.onFailure { notify("Erro ao criar arquivo: ${it.message}") }
        _state.update { it.copy(treeVersion = it.treeVersion + 1) }
    }
    fun onEditorChange(value: TextFieldValue) {
        _state.update { old ->
            val prefix = Regex("[A-Za-z_][A-Za-z0-9_]*$").find(value.text.take(value.selection.start))?.value.orEmpty()
            val suggestions = if (prefix.length >= 2) (rustWords + Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(value.text).map { it.value }.toList()).distinct().filter { it.startsWith(prefix) && it != prefix }.take(8) else emptyList()
            old.copy(editor = value, dirty = old.dirty || value.text != old.editor.text, symbols = parseSymbols(value.text), completions = suggestions)
        }
    }
    fun applyCompletion(suggestion: String) {
        val s = _state.value; val cursor = s.editor.selection.start
        val before = s.editor.text.substring(0, cursor); val prefix = Regex("[A-Za-z_][A-Za-z0-9_]*$").find(before)?.value.orEmpty()
        val start = cursor - prefix.length; val next = s.editor.text.replaceRange(start, cursor, suggestion)
        onEditorChange(TextFieldValue(next, androidx.compose.ui.text.TextRange(start + suggestion.length)))
    }
    fun saveCurrent(): Boolean {
        val s = _state.value; val file = s.currentFile ?: return true; if (!s.dirty) return true
        return try { file.writeText(s.editor.text); _state.update { it.copy(dirty = false) }; true }
        catch (e: Exception) { notify("Erro ao salvar: ${e.message}"); false }
    }
    fun runProject() {
        val project = _state.value.openProject ?: return; val file = _state.value.currentFile ?: return
        if (!saveCurrent()) return
        val safe = runCatching { file.canonicalFile.relativeTo(project.dir.canonicalFile).invariantSeparatorsPath }.getOrElse { notify("Arquivo fora do projeto"); return }
        val marker = "RF_EOF_" + UUID.randomUUID().toString().replace("-", "")
        val code = _state.value.editor.text
        val script = "cat << '$marker' > '$safe'\n$code\n$marker\ncargo run"
        execute("cargo run", script)
    }
    fun buildProject() {
        if (!saveCurrent()) return
        _state.update { it.copy(diagnostics = emptyList(), buildProgress = "Verificando projeto…") }
        execute("cargo check", "cargo check --message-format=json")
    }
    fun addDependency(spec: String) {
        val value = spec.trim()
        if (!Regex("^[A-Za-z0-9_-]+(?:@[A-Za-z0-9.*+_-]+)?$").matches(value)) { notify("Dependência inválida. Use, por exemplo, serde ou serde@1"); return }
        execute("cargo add $value", "cargo add '$value'")
    }
    fun submitTerminal(command: String) {
        val trimmed = command.trim(); if (trimmed.isEmpty()) return
        when { trimmed == "cd" || trimmed.startsWith("cd ") -> changeDirectory(trimmed.removePrefix("cd").trim()); else -> execute(trimmed, trimmed) }
    }
    fun stop() = shell.cancel()
    fun jumpToSymbol(symbol: CodeSymbol) {
        val s = _state.value; val text = s.editor.text; var offset = 0
        repeat((symbol.line - 1).coerceAtLeast(0)) { val n = text.indexOf('\n', offset); if (n >= 0) offset = n + 1 }
        _state.update { it.copy(editor = it.editor.copy(selection = androidx.compose.ui.text.TextRange(offset))) }
    }
    private fun execute(display: String, script: String) {
        val s = _state.value; val dir = s.cwd ?: return
        if (s.running) { appendTerminal("Já existe um processo em execução."); return }
        appendTerminal("$ $display"); _state.update { it.copy(running = true, buildProgress = if (display.contains("cargo")) "Iniciando: $display" else it.buildProgress) }
        shell.start(Bootstrap.command(app, script), dir, Bootstrap.env(app), { line ->
            if (line.startsWith("{")) parseCargoMessage(line)
            else { appendTerminal(line); if (line.contains("Compiling") || line.contains("Checking") || line.contains("Finished")) _state.update { it.copy(buildProgress = line) } }
        }, { code -> appendTerminal("[terminou com código $code]"); _state.update { it.copy(running = false, buildProgress = if (code == 0) "Concluído" else "Falhou (código $code)") } })
    }
    private fun parseCargoMessage(line: String) {
        runCatching {
            val json = JSONObject(line)
            if (json.optString("reason") == "compiler-message") {
                val msg = json.getJSONObject("message"); val spans = msg.optJSONArray("spans")
                val span = if (spans != null && spans.length() > 0) spans.getJSONObject(0) else JSONObject()
                val diagnostic = IdeDiagnostic(span.optString("file_name"), span.optInt("line_start", 1), span.optInt("column_start", 1), msg.optString("level", "error"), msg.optString("message"))
                _state.update { it.copy(diagnostics = (it.diagnostics + diagnostic).takeLast(100)) }
                appendTerminal("${diagnostic.severity}: ${diagnostic.file}:${diagnostic.line}:${diagnostic.column}: ${diagnostic.message}")
            } else if (json.optString("reason") == "compiler-artifact") {
                _state.update { it.copy(buildProgress = "Compilando ${json.optString("target")}") }
            }
        }
    }
    private fun parseSymbols(text: String): List<CodeSymbol> = text.lines().mapIndexedNotNull { i, line ->
        val m = Regex("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(fn|struct|enum|trait|mod|type|const|static)\\s+([A-Za-z_][A-Za-z0-9_]*)").find(line)
        m?.let { CodeSymbol(it.groupValues[2], it.groupValues[1], i + 1) }
    }
    private fun changeDirectory(path: String) {
        val current = _state.value.cwd ?: return; val root = _state.value.openProject?.dir ?: return
        val target = if (path.isEmpty()) root.canonicalFile else File(current, path).canonicalFile
        if (target.isDirectory && (target.path == root.canonicalPath || target.path.startsWith(root.canonicalPath + File.separator))) _state.update { it.copy(cwd = target) }
        else appendTerminal("cd: diretório não encontrado ou fora do projeto: $path")
    }
    private fun appendTerminal(line: String) { _state.update { it.copy(terminal = (it.terminal + line).takeLast(2000)) } }
    private fun notify(message: String) { _state.update { it.copy(message = message) } }
    fun consumeMessage() { _state.update { it.copy(message = null) } }
}
