package com.ndx.rustforge.ide.shell;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ShellRunner {

    private volatile Process process;

    public int execute(List<String> command, File dir, Map<String, String> env, Consumer<String> onLine) {
        try {
            Process started = spawn(command, dir, env);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            }
            return started.waitFor();
        } catch (IOException e) {
            onLine.accept("erro: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            process = null;
        }
    }

    public void start(List<String> command, File dir, Map<String, String> env,
                      Consumer<String> onLine, Consumer<Integer> onExit) {
        Thread worker = new Thread(() -> onExit.accept(execute(command, dir, env, onLine)), "rustforge-shell");
        worker.start();
    }

    public void cancel() {
        Process current = process;
        if (current != null) {
            current.destroy();
        }
    }

    private Process spawn(List<String> command, File dir, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir);
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(env);
        Process started = builder.start();
        process = started;
        return started;
    }
}
