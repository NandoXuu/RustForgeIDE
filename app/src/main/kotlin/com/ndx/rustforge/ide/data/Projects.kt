package com.ndx.rustforge.ide.data

import android.content.Context
import java.io.File

data class Project(val name: String, val dir: File)

enum class Template(val label: String) {
    CONSOLE("Console (Rust puro)"),
    EGUI("egui (eframe)"),
    SLINT("Slint")
}

class ProjectRepository(context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> =
        root.listFiles()
            ?.filter { File(it, "Cargo.toml").isFile }
            ?.sortedBy { it.name }
            ?.map { Project(it.name, it) }
            .orEmpty()

    fun create(rawName: String, template: Template): Project {
        val name = rawName.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_").ifEmpty { "projeto" }
        val dir = File(root, name)
        require(!dir.exists()) { "Já existe um projeto chamado $name" }
        Templates.files(template, name).forEach { (path, content) ->
            File(dir, path).apply { parentFile?.mkdirs() }.writeText(content)
        }
        return Project(name, dir)
    }

    fun delete(project: Project) {
        project.dir.deleteRecursively()
    }
}

object Templates {

    fun files(template: Template, name: String): Map<String, String> = when (template) {
        Template.CONSOLE -> mapOf(
            "Cargo.toml" to manifest(name),
            "src/main.rs" to CONSOLE_MAIN
        )
        Template.EGUI -> mapOf(
            "Cargo.toml" to manifest(name, "eframe = \"0.29\""),
            "src/main.rs" to EGUI_MAIN
        )
        Template.SLINT -> mapOf(
            "Cargo.toml" to """
                [package]
                name = "$name"
                version = "0.1.0"
                edition = "2021"
                build = "build.rs"

                [dependencies]
                slint = "1.8"

                [build-dependencies]
                slint-build = "1.8"
            """.trimIndent(),
            "build.rs" to SLINT_BUILD,
            "ui/app.slint" to SLINT_UI,
            "src/main.rs" to SLINT_MAIN
        )
    }

    private fun manifest(name: String, deps: String = "") = """
        [package]
        name = "$name"
        version = "0.1.0"
        edition = "2021"

        [dependencies]
        $deps
    """.trimIndent()

    private val CONSOLE_MAIN = """
        fn main() {
            println!("Olá, Rust!");
        }
    """.trimIndent()

    private val EGUI_MAIN = """
        use eframe::egui;

        #[derive(Default)]
        struct App {
            counter: u32,
        }

        fn main() -> eframe::Result {
            eframe::run_native(
                "RustForge",
                eframe::NativeOptions::default(),
                Box::new(|_cc| Ok(Box::<App>::default())),
            )
        }

        impl eframe::App for App {
            fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
                egui::CentralPanel::default().show(ctx, |ui| {
                    ui.heading("Olá do egui");
                    if ui.button("Contar").clicked() {
                        self.counter += 1;
                    }
                    ui.label(format!("Contador: {}", self.counter));
                });
            }
        }
    """.trimIndent()

    private val SLINT_BUILD = """
        fn main() {
            slint_build::compile("ui/app.slint").unwrap();
        }
    """.trimIndent()

    private val SLINT_UI = """
        import { Button } from "std-widgets.slint";

        export component AppWindow inherits Window {
            title: "RustForge Slint";
            preferred-width: 360px;
            preferred-height: 640px;
            in-out property <int> counter: 0;

            VerticalLayout {
                padding: 24px;
                spacing: 16px;
                Text { text: "Olá do Slint"; font-size: 24px; }
                Button {
                    text: "Contar";
                    clicked => { root.counter += 1; }
                }
                Text { text: "Contador: \{root.counter}"; }
            }
        }
    """.trimIndent()

    private val SLINT_MAIN = """
        slint::include_modules!();

        fn main() -> Result<(), slint::PlatformError> {
            let ui = AppWindow::new()?;
            ui.run()
        }
    """.trimIndent()
}
