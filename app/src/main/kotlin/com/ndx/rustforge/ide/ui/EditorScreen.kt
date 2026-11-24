package com.ndx.rustforge.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ndx.rustforge.ide.viewmodel.IdeState
import com.ndx.rustforge.ide.viewmodel.IdeViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(state: IdeState, vm: IdeViewModel) {
    val project = state.openProject ?: return
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showTerminal by remember { mutableStateOf(true) }
    var showNewFile by remember { mutableStateOf(false) }
    var showDeps by remember { mutableStateOf(false) }
    var showSymbols by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showNewFile = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Novo arquivo")
                    }
                }
                HorizontalDivider()
                FileTree(
                    root = project.dir,
                    version = state.treeVersion,
                    selected = state.currentFile,
                    onOpen = { file ->
                        vm.openFile(file)
                        scope.launch { drawer.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.currentFile?.name ?: project.name) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Arquivos")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.saveCurrent() }) {
                            Icon(Icons.Default.Save, contentDescription = "Salvar")
                        }
                        IconButton(onClick = { showSymbols = true }) {
                            Icon(Icons.Default.AccountTree, contentDescription = "Navegar símbolos")
                        }
                        IconButton(onClick = { showDeps = true }) {
                            Icon(Icons.Default.LibraryAdd, contentDescription = "Dependências Cargo")
                        }
                        IconButton(onClick = { vm.buildProject() }) {
                            Icon(Icons.Default.Build, contentDescription = "Cargo check")
                        }
                        FilledIconButton(onClick = { vm.runProject() }, enabled = !state.running) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Executar")
                        }
                        IconButton(onClick = { showTerminal = !showTerminal }) {
                            Icon(Icons.Default.Terminal, contentDescription = "Terminal")
                        }
                        IconButton(onClick = { vm.closeProject() }) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar projeto")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.buildProgress.isNotBlank()) {
                    Text(state.buildProgress, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                }
                if (state.completions.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.completions.forEach { item -> SuggestionChip(onClick = { vm.applyCompletion(item) }, label = { Text(item) }) }
                    }
                }
                CodeEditor(
                    value = state.editor,
                    onValueChange = vm::onEditorChange,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                if (state.diagnostics.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 110.dp).background(MaterialTheme.colorScheme.errorContainer).verticalScroll(rememberScrollState()).padding(8.dp)) {
                        state.diagnostics.takeLast(5).forEach { d ->
                            Text("${d.severity.uppercase()}  ${d.file}:${d.line}:${d.column}  ${d.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                if (showTerminal) {
                    TerminalPanel(
                        lines = state.terminal,
                        running = state.running,
                        onSubmit = { vm.submitTerminal(it) },
                        onStop = { vm.stop() },
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    )
                }
            }
        }
    }

    if (showSymbols) {
        AlertDialog(onDismissRequest = { showSymbols = false }, title = { Text("Navegação de código") },
            text = { if (state.symbols.isEmpty()) Text("Nenhuma função, struct, enum ou módulo encontrado neste arquivo.") else LazyColumn { items(state.symbols) { symbol -> TextButton(onClick = { vm.jumpToSymbol(symbol); showSymbols = false }, modifier = Modifier.fillMaxWidth()) { Text("${symbol.kind}  ${symbol.name}  ·  linha ${symbol.line}") } } } },
            confirmButton = { TextButton(onClick = { showSymbols = false }) { Text("Fechar") } })
    }
    if (showDeps) {
        DependencyDialog(onDismiss = { showDeps = false }, onAdd = { vm.addDependency(it); showDeps = false })
    }
    if (showNewFile) {
        NewFileDialog(
            onDismiss = { showNewFile = false },
            onConfirm = { path ->
                vm.newFile(path)
                showNewFile = false
            }
        )
    }
}

@Composable
private fun DependencyDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var spec by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adicionar dependência") },
        text = { OutlinedTextField(value = spec, onValueChange = { spec = it }, label = { Text("crate ou crate@versão") }, placeholder = { Text("serde@1") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onAdd(spec) }, enabled = spec.isNotBlank()) { Text("cargo add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun NewFileDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("src/") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo arquivo") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Caminho relativo") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(path) }, enabled = path.isNotBlank()) { Text("Criar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun FileTree(root: File, version: Int, selected: File?, onOpen: (File) -> Unit) {
    val children = remember(root, version) { listChildren(root) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        children.forEach { TreeNode(it, 0, selected, onOpen) }
    }
}

private fun listChildren(dir: File): List<File> =
    dir.listFiles()
        ?.filter { !it.name.startsWith(".") && it.name != "target" }
        ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        .orEmpty()

@Composable
private fun TreeNode(file: File, depth: Int, selected: File?, onOpen: (File) -> Unit) {
    var expanded by remember(file) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected == file) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable {
                if (file.isDirectory) expanded = !expanded else onOpen(file)
            }
            .padding(start = (depth * 16 + 16).dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(file.name, style = MaterialTheme.typography.bodyMedium)
    }
    if (file.isDirectory && expanded) {
        listChildren(file).forEach { TreeNode(it, depth + 1, selected, onOpen) }
    }
}

@Composable
fun TerminalPanel(
    lines: List<String>,
    running: Boolean,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Surface(modifier = modifier, color = Color(0xFF0D0D0D)) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFD4D4D4)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$ ", fontFamily = FontFamily.Monospace, color = Color(0xFF7DD3C0))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onSubmit(input)
                        input = ""
                    }),
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                )
                if (running) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Parar", tint = Color.White)
                    }
                }
            }
        }
    }
}
