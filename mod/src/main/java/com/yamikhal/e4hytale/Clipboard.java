package com.yamikhal.e4hytale;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// puts an address (ASCII) into the clipboard of the machine the server runs on. Only useful for a hosted world, whose
// server runs on the owner's PC next to the game. Java's own clipboard is out of reach (the server forces AWT headless
// mode), so this goes through the system's clipboard tool. Blocks for up to two seconds, call it off the game threads
final class Clipboard {

    private static final long TIMEOUT_SECONDS = 2;

    private Clipboard() {}

    // false if no clipboard tool ran successfully
    static boolean copy(String text) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // by full path, so nothing named clip earlier in PATH runs instead. clip.exe reads the console code page,
            // ASCII is the same in all of them (and addresses are ASCII)
            String system = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            return run(List.of(Path.of(system, "System32", "clip.exe").toString()), text, StandardCharsets.US_ASCII);
        }

        if (os.contains("mac")) {
            return run(List.of("/usr/bin/pbcopy"), text, StandardCharsets.UTF_8);
        }

        return run(List.of("wl-copy"), text, StandardCharsets.UTF_8)
                || run(List.of("xclip", "-selection", "clipboard"), text, StandardCharsets.UTF_8)
                || run(List.of("xsel", "--clipboard", "--input"), text, StandardCharsets.UTF_8);
    }

    private static boolean run(List<String> command, String text, Charset charset) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (OutputStream in = process.getOutputStream()) {
                in.write(text.getBytes(charset));
            }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (IOException e) {
            // the tool isn't installed (e.g. no wl-copy on X11), the caller tries the next one
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
