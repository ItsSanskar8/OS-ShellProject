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

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            String input = reader.readLine();

            if (input == null) {
                break;
            }

            input = input.trim();

            if (input.isEmpty()) {
                continue;
            }

            List<String> tokens = tokenize(input);

            if (tokens.isEmpty()) {
                continue;
            }

            ParsedCommand parsedCommand = parseRedirection(tokens);

            if (parsedCommand.commandParts.isEmpty()) {
                continue;
            }

            String command = parsedCommand.commandParts.get(0);

            if (command.equals("exit")) {
                break;
            }

            if (command.equals("echo")) {
                String output = "";

                if (parsedCommand.commandParts.size() > 1) {
                    output = String.join(" ", parsedCommand.commandParts.subList(1, parsedCommand.commandParts.size()));
                }

                writeStdout(output, parsedCommand.stdoutFile);
                continue;
            }

            if (command.equals("pwd")) {
                writeStdout(currentDirectory.getAbsolutePath(), parsedCommand.stdoutFile);
                continue;
            }

            if (command.equals("cd")) {
                handleCd(parsedCommand.commandParts);
                continue;
            }

            if (command.equals("type")) {
                handleType(parsedCommand.commandParts, parsedCommand.stdoutFile);
                continue;
            }

            runExternalCommand(parsedCommand.commandParts, parsedCommand.stdoutFile);
        }
    }

    private static ParsedCommand parseRedirection(List<String> tokens) {
        List<String> commandParts = new ArrayList<>();
        String stdoutFile = null;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(i + 1);
                    i++;
                }
            } else {
                commandParts.add(token);
            }
        }

        return new ParsedCommand(commandParts, stdoutFile);
    }

    private static void handleCd(List<String> commandParts) {
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
            } else {
                System.out.println("cd: " + path + ": No such file or directory");
            }
        } catch (Exception e) {
            System.out.println("cd: " + path + ": No such file or directory");
        }
    }

    private static void handleType(List<String> commandParts, String stdoutFile) {
        if (commandParts.size() < 2) {
            return;
        }

        String target = commandParts.get(1);

        if (isBuiltin(target)) {
            writeStdout(target + " is a shell builtin", stdoutFile);
            return;
        }

        String executablePath = findExecutable(target);

        if (executablePath != null) {
            writeStdout(target + " is " + executablePath, stdoutFile);
        } else {
            System.out.println(target + ": not found");
        }
    }

    private static boolean isBuiltin(String command) {
        return command.equals("exit")
                || command.equals("echo")
                || command.equals("type")
                || command.equals("pwd")
                || command.equals("cd");
    }

    private static void runExternalCommand(List<String> commandParts, String stdoutFile) {
        String command = commandParts.get(0);

        String executablePath = findExecutable(command);

        if (executablePath == null) {
            System.out.println(command + ": command not found");
            return;
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.directory(currentDirectory);

            if (stdoutFile != null) {
                File outputFile = resolveFile(stdoutFile);
                processBuilder.redirectOutput(outputFile);
            } else {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            // Important: stderr should still appear on terminal.
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process process = processBuilder.start();
            process.waitFor();

        } catch (Exception e) {
            System.out.println(command + ": command not found");
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

    private static void writeStdout(String text, String stdoutFile) {
        if (stdoutFile == null) {
            System.out.println(text);
            return;
        }

        try {
            File outputFile = resolveFile(stdoutFile);

            try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
                outputStream.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            System.out.println(text);
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

                if (c == '>') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add(">");
                    continue;
                }

                if (c == '1' && i + 1 < input.length() && input.charAt(i + 1) == '>') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add("1>");
                    i++;
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

        ParsedCommand(List<String> commandParts, String stdoutFile) {
            this.commandParts = commandParts;
            this.stdoutFile = stdoutFile;
        }
    }
}