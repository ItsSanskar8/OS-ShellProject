import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Main {

    private static File currentDirectory = new File(System.getProperty("user.dir"));
    private static final List<Job> jobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            reapCompletedJobs();

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

            executeLine(input);
        }
    }

    private static void executeLine(String input) {
        List<String> tokens = tokenize(input);

        if (tokens.isEmpty()) {
            return;
        }

        boolean background = false;

        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
            background = true;
            tokens.remove(tokens.size() - 1);
        }

        ParsedCommand parsedCommand = parseRedirection(tokens);

        if (parsedCommand.commandParts.isEmpty()) {
            return;
        }

        if (containsPipe(parsedCommand.commandParts)) {
            runPipeline(parsedCommand, background, input);
            return;
        }

        String command = parsedCommand.commandParts.get(0);

        if (!background && command.equals("exit")) {
            System.exit(0);
        }

        if (command.equals("echo")) {
            createEmptyFileIfNeeded(parsedCommand.stderrFile, parsedCommand.stderrAppend);

            String output = "";

            if (parsedCommand.commandParts.size() > 1) {
                output = String.join(" ", parsedCommand.commandParts.subList(1, parsedCommand.commandParts.size()));
            }

            writeStdout(output, parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
            return;
        }

        if (command.equals("pwd")) {
            createEmptyFileIfNeeded(parsedCommand.stderrFile, parsedCommand.stderrAppend);
            writeStdout(currentDirectory.getAbsolutePath(), parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
            return;
        }

        if (command.equals("cd")) {
            handleCd(parsedCommand.commandParts, parsedCommand.stderrFile, parsedCommand.stderrAppend);
            return;
        }

        if (command.equals("type")) {
            handleType(
                    parsedCommand.commandParts,
                    parsedCommand.stdoutFile,
                    parsedCommand.stdoutAppend,
                    parsedCommand.stderrFile,
                    parsedCommand.stderrAppend
            );
            return;
        }

        if (command.equals("jobs")) {
            handleJobs(parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
            createEmptyFileIfNeeded(parsedCommand.stderrFile, parsedCommand.stderrAppend);
            return;
        }

        runExternalCommand(
                parsedCommand.commandParts,
                parsedCommand.stdoutFile,
                parsedCommand.stdoutAppend,
                parsedCommand.stderrFile,
                parsedCommand.stderrAppend,
                background,
                input
        );
    }

    private static void runExternalCommand(
            List<String> commandParts,
            String stdoutFile,
            boolean stdoutAppend,
            String stderrFile,
            boolean stderrAppend,
            boolean background,
            String originalInput
    ) {
        String command = commandParts.get(0);
        String executablePath = findExecutable(command);

        if (executablePath == null) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
            createEmptyFileIfNeeded(stdoutFile, stdoutAppend);
            return;
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.directory(currentDirectory);

            configureProcessOutput(processBuilder, stdoutFile, stdoutAppend);
            configureProcessError(processBuilder, stderrFile, stderrAppend);

            Process process = processBuilder.start();

            if (background) {
                int jobId = nextJobId();

                Job job = new Job(
                        jobId,
                        originalInputWithoutAmpersand(originalInput),
                        List.of(process)
                );

                jobs.add(job);

                System.out.println("[" + jobId + "] " + process.pid());
            } else {
                process.waitFor();
            }

        } catch (Exception e) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
        }
    }

    private static void runPipeline(ParsedCommand parsedCommand, boolean background, String originalInput) {
        List<List<String>> commands = splitPipeline(parsedCommand.commandParts);

        if (commands.isEmpty()) {
            return;
        }

        if (background) {
            runPipelineInBackground(parsedCommand, originalInput);
            return;
        }

        byte[] input = new byte[0];

        for (int i = 0; i < commands.size(); i++) {
            List<String> commandParts = commands.get(i);

            boolean last = i == commands.size() - 1;

            CommandResult result = executeForPipeline(
                    commandParts,
                    input,
                    last ? parsedCommand.stderrFile : null,
                    last && parsedCommand.stderrAppend
            );

            input = result.stdout;
        }

        String finalOutput = new String(input, StandardCharsets.UTF_8);

        if (parsedCommand.stdoutFile != null) {
            writeRawToFile(finalOutput, parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
        } else {
            System.out.print(finalOutput);
            System.out.flush();
        }
    }

    private static void runPipelineInBackground(ParsedCommand parsedCommand, String originalInput) {
        Thread worker = new Thread(() -> runPipeline(parsedCommand, false, originalInput));
        worker.setDaemon(false);
        worker.start();

        int jobId = nextJobId();

        Job job = new Job(
                jobId,
                originalInputWithoutAmpersand(originalInput),
                new ArrayList<>()
        );

        job.workerThread = worker;

        jobs.add(job);

        System.out.println("[" + jobId + "] " + worker.getId());
    }

    private static CommandResult executeForPipeline(
            List<String> commandParts,
            byte[] stdin,
            String stderrFile,
            boolean stderrAppend
    ) {
        if (commandParts.isEmpty()) {
            return new CommandResult(new byte[0]);
        }

        String command = commandParts.get(0);

        try {
            if (command.equals("echo")) {
                String output = "";

                if (commandParts.size() > 1) {
                    output = String.join(" ", commandParts.subList(1, commandParts.size()));
                }

                return new CommandResult((output + "\n").getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("pwd")) {
                return new CommandResult((currentDirectory.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("type")) {
                if (commandParts.size() < 2) {
                    return new CommandResult(new byte[0]);
                }

                String target = commandParts.get(1);
                String output;

                if (isBuiltin(target)) {
                    output = target + " is a shell builtin";
                } else {
                    String executablePath = findExecutable(target);

                    if (executablePath != null) {
                        output = target + " is " + executablePath;
                    } else {
                        output = target + ": not found";
                    }
                }

                return new CommandResult((output + "\n").getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("jobs")) {
                StringBuilder output = new StringBuilder();

                List<Job> sortedJobs = new ArrayList<>(jobs);
                sortedJobs.sort(Comparator.comparingInt(job -> job.id));

                for (Job job : sortedJobs) {
                    if (job.isRunning()) {
                        output.append("[")
                                .append(job.id)
                                .append("] Running ")
                                .append(job.command)
                                .append("\n");
                    }
                }

                return new CommandResult(output.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("cd") || command.equals("exit")) {
                return new CommandResult(new byte[0]);
            }

            String executablePath = findExecutable(command);

            if (executablePath == null) {
                writeStderr(command + ": command not found", stderrFile, stderrAppend);
                return new CommandResult(new byte[0]);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.directory(currentDirectory);

            configureProcessError(processBuilder, stderrFile, stderrAppend);

            Process process = processBuilder.start();

            try (OutputStream processInput = process.getOutputStream()) {
                processInput.write(stdin);
                processInput.flush();
            }

            byte[] stdout = process.getInputStream().readAllBytes();

            process.waitFor();

            return new CommandResult(stdout);

        } catch (Exception e) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
            return new CommandResult(new byte[0]);
        }
    }

    private static boolean containsPipe(List<String> tokens) {
        for (String token : tokens) {
            if (token.equals("|")) {
                return true;
            }
        }

        return false;
    }

    private static List<List<String>> splitPipeline(List<String> tokens) {
        List<List<String>> commands = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String token : tokens) {
            if (token.equals("|")) {
                if (!current.isEmpty()) {
                    commands.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(token);
            }
        }

        if (!current.isEmpty()) {
            commands.add(current);
        }

        return commands;
    }

    private static void configureProcessOutput(
            ProcessBuilder processBuilder,
            String stdoutFile,
            boolean stdoutAppend
    ) {
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
    }

    private static void configureProcessError(
            ProcessBuilder processBuilder,
            String stderrFile,
            boolean stderrAppend
    ) {
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
    }

    private static void handleCd(
            List<String> commandParts,
            String stderrFile,
            boolean stderrAppend
    ) {
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
                createEmptyFileIfNeeded(stderrFile, stderrAppend);
            } else {
                writeStderr("cd: " + path + ": No such file or directory", stderrFile, stderrAppend);
            }

        } catch (Exception e) {
            writeStderr("cd: " + path + ": No such file or directory", stderrFile, stderrAppend);
        }
    }

    private static void handleType(
            List<String> commandParts,
            String stdoutFile,
            boolean stdoutAppend,
            String stderrFile,
            boolean stderrAppend
    ) {
        createEmptyFileIfNeeded(stderrFile, stderrAppend);

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

    private static void handleJobs(String stdoutFile, boolean stdoutAppend) {
        List<Job> sortedJobs = new ArrayList<>(jobs);
        sortedJobs.sort(Comparator.comparingInt(job -> job.id));

        StringBuilder output = new StringBuilder();

        for (Job job : sortedJobs) {
            if (job.isRunning()) {
                output.append("[")
                        .append(job.id)
                        .append("] Running ")
                        .append(job.command)
                        .append("\n");
            }
        }

        if (output.length() == 0) {
            return;
        }

        String text = output.toString();

        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }

        writeStdout(text, stdoutFile, stdoutAppend);
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
            } else if (token.equals("2>")) {
                if (i + 1 < tokens.size()) {
                    stderrFile = tokens.get(i + 1);
                    stderrAppend = false;
                    i++;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < tokens.size()) {
                    stderrFile = tokens.get(i + 1);
                    stderrAppend = true;
                    i++;
                }
            } else {
                commandParts.add(token);
            }
        }

        return new ParsedCommand(
                commandParts,
                stdoutFile,
                stdoutAppend,
                stderrFile,
                stderrAppend
        );
    }

    private static boolean isBuiltin(String command) {
        return command.equals("exit")
                || command.equals("echo")
                || command.equals("type")
                || command.equals("pwd")
                || command.equals("cd")
                || command.equals("jobs");
    }

    private static String findExecutable(String command) {
        if (command.contains(File.separator)) {
            File file = new File(command);

            if (!file.isAbsolute()) {
                file = new File(currentDirectory, command);
            }

            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }

            return null;
        }

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

    private static void writeStdout(
            String text,
            String stdoutFile,
            boolean append
    ) {
        if (stdoutFile == null) {
            System.out.println(text);
            return;
        }

        writeToFile(text, stdoutFile, append);
    }

    private static void writeStderr(
            String text,
            String stderrFile,
            boolean append
    ) {
        if (stderrFile == null) {
            System.out.println(text);
            return;
        }

        writeToFile(text, stderrFile, append);
    }

    private static void writeToFile(
            String text,
            String path,
            boolean append
    ) {
        try {
            File file = resolveFile(path);

            try (FileOutputStream outputStream = new FileOutputStream(file, append)) {
                outputStream.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            System.out.println(text);
        }
    }

    private static void writeRawToFile(
            String text,
            String path,
            boolean append
    ) {
        try {
            File file = resolveFile(path);

            try (FileOutputStream outputStream = new FileOutputStream(file, append)) {
                outputStream.write(text.getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            System.out.print(text);
        }
    }

    private static void createEmptyFileIfNeeded(
            String path,
            boolean append
    ) {
        if (path == null) {
            return;
        }

        try {
            File file = resolveFile(path);

            if (append && file.exists()) {
                return;
            }

            try (FileOutputStream outputStream = new FileOutputStream(file, append)) {
                // Create file if needed.
                // For overwrite mode, truncate file.
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

    private static int nextJobId() {
        Set<Integer> used = new HashSet<>();

        for (Job job : jobs) {
            if (job.isRunning()) {
                used.add(job.id);
            }
        }

        int id = 1;

        while (used.contains(id)) {
            id++;
        }

        return id;
    }

    private static void reapCompletedJobs() {
        jobs.removeIf(job -> !job.isRunning());
    }

    private static String originalInputWithoutAmpersand(String input) {
        String trimmed = input.trim();

        if (trimmed.endsWith("&")) {
            return trimmed.substring(0, trimmed.length() - 1).trim();
        }

        return trimmed;
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

                if (c == '|') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add("|");
                    continue;
                }

                if (c == '&') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add("&");
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

        ParsedCommand(
                List<String> commandParts,
                String stdoutFile,
                boolean stdoutAppend,
                String stderrFile,
                boolean stderrAppend
        ) {
            this.commandParts = commandParts;
            this.stdoutFile = stdoutFile;
            this.stdoutAppend = stdoutAppend;
            this.stderrFile = stderrFile;
            this.stderrAppend = stderrAppend;
        }
    }

    private static class Job {
        int id;
        String command;
        List<Process> processes;
        Thread workerThread;

        Job(int id, String command, List<Process> processes) {
            this.id = id;
            this.command = command;
            this.processes = processes;
        }

        boolean isRunning() {
            if (workerThread != null) {
                return workerThread.isAlive();
            }

            for (Process process : processes) {
                if (process.isAlive()) {
                    return true;
                }
            }

            return false;
        }
    }

    private static class CommandResult {
        byte[] stdout;

        CommandResult(byte[] stdout) {
            this.stdout = stdout;
        }
    }
}