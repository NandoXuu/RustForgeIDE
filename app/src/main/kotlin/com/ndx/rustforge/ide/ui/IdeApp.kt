package com.ndx.rustforge.ide.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.ndx.rustforge.ide.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ndx.rustforge.ide.data.Project
import com.ndx.rustforge.ide.data.Template
import com.ndx.rustforge.ide.viewmodel.IdeViewModel
import com.ndx.rustforge.ide.viewmodel.SetupViewModel

@Composable
fun IdeApp(vm: IdeViewModel = viewModel(), setupVm: SetupViewModel = viewModel()) {
    val setup by setupVm.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ready by remember { mutableStateOf(setupVm.alreadyReady) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    if (!ready) {
        SetupScreen(state = setup, onStart = setupVm::start, onContinue = { ready = true })
    } else {
        BackHandler(enabled = state.openProject != null) { vm.closeProject() }
        if (state.openProject == null) {
            ProjectListScreen(
                projects = state.projects,
                onOpen = vm::openProject,
                onCreate = vm::createProject,
                onDelete = vm::deleteProject
            )
        } else {
            EditorScreen(state = state, vm = vm)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    projects: List<Project>,
    onOpen: (Project) -> Unit,
    onCreate: (String, Template) -> Unit,
    onDelete: (Project) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.rustforge_logo), contentDescription = "RustForge", modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Text("RustForge")
            }
        }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Novo projeto") }
            )
        }
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nenhum projeto ainda. Crie o primeiro!")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(projects, key = { it.name }) { project ->
                    ElevatedCard(onClick = { onOpen(project) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text(project.dir.path, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onDelete(project) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Excluir projeto")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, template ->
                onCreate(name, template)
                showCreate = false
            }
        )
    }
}

@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onConfirm: (String, Template) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(Template.CONSOLE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo projeto Rust") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Template.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = template == option, onClick = { template = option })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = template == option, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, template) }, enabled = name.isNotBlank()) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
