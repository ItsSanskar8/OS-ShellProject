import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static File currentDirectory = new File(System.getProperty("user.dir"));

    public static void main(String[] args) throws Exception {
        // Switch to raw character input to support TAB completion
        System.out.print("$ ");
        System.out.flush();

        StringBuilder buffer = new StringBuilder();

        while (true) {
            int r = System.in.read();

            if (r == -1) {
                break; // EOF
            }

            char c = (char) r;

            // Ignore carriage return
            if (c == '\r') {
                continue;
            }

            // Enter: process the current buffer
            if (c == '\n') {
                System.out.print("\n");
                String input = buffer.toString().trim();
                buffer.setLength(0);

                if (input.isEmpty()) {
                    System.out.print("$ ");
                    System.out.flush();
                    continue;
                }

                executeLine(input);

                System.out.print("$ ");
                System.out.flush();
                continue;
            }

            // TAB: attempt builtin completion (only for first token)
            if (c == '\t') {
                String current = buffer.toString();
                int lastSpace = current.lastIndexOf(' ');

                if (lastSpace == -1) {
                    String[] builtins = new String[]{"echo", "exit", "type", "pwd", "cd", "jobs"};
                    List<String> matches = new ArrayList<>();

                    for (String b : builtins) {
                        if (b.startsWith(current)) {
                            matches.add(b);
                        }
                    }

                    if (matches.size() == 1) {
                        String match = matches.get(0);
                        String suffix = match.substring(current.length());
                        buffer.append(suffix).append(' ');
                        System.out.print(suffix + " ");
                        System.out.flush();
                    }
                }

                continue;
            }

            // Backspace (127) or Ctrl-H (8)
            if (r == 127 || r == 8) {
                if (buffer.length() > 0) {
                    buffer.setLength(buffer.length() - 1);
                    // Erase last character on terminal
                    System.out.print("\b \b");
                    System.out.flush();
                }

                continue;
            }

            // Regular character: echo and append
            buffer.append(c);
            System.out.print(c);
            System.out.flush();
        }
    }

    private static void executeLine(String input) {
        List<String> tokens = tokenize(input);

        if (tokens.isEmpty()) {
            return;
        }

        ParsedCommand parsedCommand = parseRedirection(tokens);

        if (parsedCommand.commandParts.isEmpty()) {
            return;
        }

        String command = parsedCommand.commandParts.get(0);

        if (command.equals("exit")) {
            System.exit(0);
        }

        if (command.equals("echo")) {
            createEmptyFileIfNeeded(parsedCommand.stderrFile);

            String output = "";

            if (parsedCommand.commandParts.size() > 1) {
                output = String.join(" ", parsedCommand.commandParts.subList(1, parsedCommand.commandParts.size()));
            }

            writeStdout(output, parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
            return;
        }

        if (command.equals("pwd")) {
            createEmptyFileIfNeeded(parsedCommand.stderrFile);
            writeStdout(currentDirectory.getAbsolutePath(), parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
            return;
        }

        if (command.equals("cd")) {
            handleCd(parsedCommand.commandParts, parsedCommand.stderrFile, parsedCommand.stderrAppend);
            return;
        }

        if (command.equals("type")) {
            handleType(parsedCommand.commandParts, parsedCommand.stdoutFile, parsedCommand.stdoutAppend, parsedCommand.stderrFile);
            return;
        }

        if (command.equals("jobs")) {
            createEmptyFileIfNeeded(parsedCommand.stderrFile);
            return;
        }

        runExternalCommand(
                parsedCommand.commandParts,
                parsedCommand.stdoutFile,
                parsedCommand.stdoutAppend,
                parsedCommand.stderrFile,
                parsedCommand.stderrAppend
        );
    }

    private static ParsedCommand parseRedirection(List<String> tokens) {
        List<String> commandParts = new ArrayList<>();

        String stdoutFile = null;
        boolean stdoutAppend = false;

        String stderrFile = null;
        boolean stderrAppend = false;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(i + 1);
                    stdoutAppend = false;
                    i++;
                }
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(i + 1);
                    stdoutAppend = true;
                    i++;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < tokens.size()) {
                    stderrFile = tokens.get(i + 1);
                    stderrAppend = true;
                    i++;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < tokens.size()) {
                    stderrFile = tokens.get(i + 1);
                    i++;
                }
            } else {
                commandParts.add(token);
            }
        }

        return new ParsedCommand(commandParts, stdoutFile, stdoutAppend, stderrFile, stderrAppend);
    }

    private static void handleCd(List<String> commandParts, String stderrFile, boolean stderrAppend) {
        String path;

        if (commandParts.size() < 2) {
            path = System.getenv("HOME");
        } else {
            path = commandParts.get(1);
        }

        File target;

        if (path.equals("~")) {
            target = new File(System.getenv("HOME"));
        } else if (path.startsWith("/")) {
            target = new File(path);
        } else {
            target = new File(currentDirectory, path);
        }

        try {
            File resolved = target.getCanonicalFile();

            if (resolved.exists() && resolved.isDirectory()) {
                currentDirectory = resolved;
                createEmptyFileIfNeeded(stderrFile);
            } else {
                writeStderr("cd: " + path + ": No such file or directory", stderrFile, stderrAppend);
            }
        } catch (Exception e) {
            writeStderr("cd: " + path + ": No such file or directory", stderrFile, stderrAppend);
        }
    }

    private static void handleType(List<String> commandParts, String stdoutFile, boolean stdoutAppend, String stderrFile) {
        createEmptyFileIfNeeded(stderrFile);

        if (commandParts.size() < 2) {
            return;
        }

        String target = commandParts.get(1);

        if (isBuiltin(target)) {
            writeStdout(target + " is a shell builtin", stdoutFile, stdoutAppend);
            return;
        }

        String executablePath = findExecutable(target);

        if (executablePath != null) {
            writeStdout(target + " is " + executablePath, stdoutFile, stdoutAppend);
        } else {
            writeStdout(target + ": not found", stdoutFile, stdoutAppend);
        }
    }

    private static boolean isBuiltin(String command) {
        return command.equals("exit")
                || command.equals("echo")
                || command.equals("type")
                || command.equals("jobs")
                || command.equals("pwd")
                || command.equals("cd");
    }

    private static void runExternalCommand(List<String> commandParts, String stdoutFile, boolean stdoutAppend, String stderrFile, boolean stderrAppend) {
        String command = commandParts.get(0);

        String executablePath = findExecutable(command);

        if (executablePath == null) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
            createEmptyFileIfNeeded(stdoutFile);
            return;
        }

        try {
            /*
             * Important:
             * Do NOT replace commandParts[0] with executablePath.
             * CodeCrafters expects argv[0] to remain the original command name.
             *
             * Wrong:
             * /tmp/ant/custom_exe_8130
             *
             * Correct:
             * custom_exe_8130
             */
            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.directory(currentDirectory);

            if (stdoutFile != null) {
                File outputFile = resolveFile(stdoutFile);

                if (stdoutAppend) {
                    processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile));
                } else {
                    processBuilder.redirectOutput(outputFile);
                }
            } else {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (stderrFile != null) {
                File errorFile = resolveFile(stderrFile);

                if (stderrAppend) {
                    processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(errorFile));
                } else {
                    processBuilder.redirectError(errorFile);
                }
            } else {
                processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            Process process = processBuilder.start();
            process.waitFor();

        } catch (Exception e) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
        }
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }

        String[] directories = pathEnv.split(File.pathSeparator);

        for (String directory : directories) {
            File file = new File(directory, command);

            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    private static void writeStdout(String text, String stdoutFile, boolean append) {
        if (stdoutFile == null) {
            System.out.println(text);
            return;
        }

        writeToFile(text, stdoutFile, append);
    }

    private static void writeStderr(String text, String stderrFile, boolean stderrAppend) {
        if (stderrFile == null) {
            System.out.println(text);
            return;
        }

        writeToFile(text, stderrFile, stderrAppend);
    }

    private static void writeToFile(String text, String path, boolean append) {
        try {
            File file = resolveFile(path);

            try (FileOutputStream outputStream = new FileOutputStream(file, append)) {
                outputStream.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            System.out.println(text);
        }
    }

    private static void createEmptyFileIfNeeded(String path) {
        if (path == null) {
            return;
        }

        try {
            File file = resolveFile(path);

            // Create file if it doesn't exist. Do not truncate existing files.
            try (FileOutputStream outputStream = new FileOutputStream(file, true)) {
                // Create or leave existing file intact.
            }

        } catch (Exception ignored) {
        }
    }

    private static File resolveFile(String path) {
        File file = new File(path);

        if (file.isAbsolute()) {
            return file;
        }

        return new File(currentDirectory, path);
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaping = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }

            if (c == '\\' && !inSingleQuotes) {
                escaping = true;
                continue;
            }

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

            if (!inSingleQuotes && !inDoubleQuotes) {
                if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }

                if (c == '1'
                        && i + 2 < input.length()
                        && input.charAt(i + 1) == '>'
                        && input.charAt(i + 2) == '>') {

                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add("1>>");
                    i += 2;
                    continue;
                }

                if (c == '1'
                        && i + 1 < input.length()
                        && input.charAt(i + 1) == '>') {

                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add("1>");
                    i++;
                    continue;
                }

                if (c == '2'

                        && i + 2 < input.length()
                        && input.charAt(i + 1) == '>'
                        && input.charAt(i + 2) == '>') {

                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add("2>>");
                    i += 2;
                    continue;

                }

                if (c == '2'
                        && i + 1 < input.length()
                        && input.charAt(i + 1) == '>') {

                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add("2>");
                    i++;
                    continue;
                }

                if (c == '>'
                        && i + 1 < input.length()
                        && input.charAt(i + 1) == '>') {

                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add(">>");
                    i++;
                    continue;
                }

                if (c == '>') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add(">");
                    continue;
                }
            }

            current.append(c);
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static class ParsedCommand {
        List<String> commandParts;

        String stdoutFile;
        boolean stdoutAppend;

        String stderrFile;
        boolean stderrAppend;

        ParsedCommand(List<String> commandParts, String stdoutFile, boolean stdoutAppend, String stderrFile, boolean stderrAppend) {
            this.commandParts = commandParts;
            this.stdoutFile = stdoutFile;
            this.stdoutAppend = stdoutAppend;
            this.stderrFile = stderrFile;
            this.stderrAppend = stderrAppend;
        }
    }
}