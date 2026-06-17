import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    private static File currentDirectory = new File(System.getProperty("user.dir"));

    private static final List<Job> jobs = new ArrayList<>();
    private static final List<String> history = new ArrayList<>();
    private static final Map<String, String> variables = new LinkedHashMap<>();
    private static final Map<String, CompletionSpec> completionSpecs = new HashMap<>();

    private static String lastCompletionBuffer = null;
    private static int repeatedTabCount = 0;
    private static int historyCursor = 0;

    public static void main(String[] args) throws Exception {
        loadHistoryOnStartup();
        enableRawMode();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            restoreTerminal();
            saveHistoryOnExit();
        }));

        while (true) {
            reapCompletedJobs();
            System.out.print("$ ");
            System.out.flush();

            String input = readInteractiveLine();

            if (input == null) {
                break;
            }

            input = input.trim();

            if (input.isEmpty()) {
                continue;
            }

            String expandedHistory = expandHistoryCommand(input);

            if (expandedHistory == null) {
                continue;
            }

            if (!expandedHistory.equals(input)) {
                System.out.println(expandedHistory);
                input = expandedHistory;
            }

            history.add(input);
            historyCursor = history.size();

            executeLine(input);
        }

        saveHistoryOnExit();
        restoreTerminal();
    }

    private static String readInteractiveLine() throws Exception {
        StringBuilder buffer = new StringBuilder();
        historyCursor = history.size();
        lastCompletionBuffer = null;
        repeatedTabCount = 0;

        while (true) {
            int value = System.in.read();

            if (value == -1) {
                return null;
            }

            char c = (char) value;

            if (c == '\n' || c == '\r') {
                System.out.println();
                System.out.flush();
                return buffer.toString();
            }

            if (c == '\t') {
                handleCompletion(buffer);
                continue;
            }

            if (c == 127 || c == 8) {
                resetTabState();
                if (buffer.length() > 0) {
                    buffer.deleteCharAt(buffer.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }
                continue;
            }

            if (c == 3) {
                resetTabState();
                buffer.setLength(0);
                System.out.println("^C");
                System.out.flush();
                return "";
            }

            if (c == 27) {
                handleEscapeSequence(buffer);
                continue;
            }

            resetTabState();
            buffer.append(c);
            System.out.print(c);
            System.out.flush();
        }
    }

    private static void handleEscapeSequence(StringBuilder buffer) throws Exception {
        int first = System.in.read();
        int second = System.in.read();

        if (first != '[') {
            return;
        }

        if (second == 'A') {
            if (!history.isEmpty() && historyCursor > 0) {
                historyCursor--;
                replaceInputBuffer(buffer, history.get(historyCursor));
            }
        } else if (second == 'B') {
            if (historyCursor < history.size() - 1) {
                historyCursor++;
                replaceInputBuffer(buffer, history.get(historyCursor));
            } else {
                historyCursor = history.size();
                replaceInputBuffer(buffer, "");
            }
        }
    }

    private static void replaceInputBuffer(StringBuilder buffer, String value) {
        buffer.setLength(0);
        buffer.append(value);
        System.out.print("\r$ " + value + "\033[K");
        System.out.flush();
    }

    private static void handleCompletion(StringBuilder buffer) {
        String before = buffer.toString();

        if (before.equals(lastCompletionBuffer)) {
            repeatedTabCount++;
        } else {
            lastCompletionBuffer = before;
            repeatedTabCount = 1;
        }

        CompletionContext context = getCompletionContext(before);
        List<CompletionCandidate> candidates;

        if (context.commandPosition) {
            candidates = getCommandCompletionCandidates(context.currentWord);
        } else {
            candidates = getArgumentCompletionCandidates(context);
        }

        candidates = uniqueAndSortedCandidates(candidates);

        if (candidates.isEmpty()) {
            bell();
            return;
        }

        applyCompletion(buffer, context, candidates);
    }

    private static void applyCompletion(
            StringBuilder buffer,
            CompletionContext context,
            List<CompletionCandidate> candidates
    ) {
        if (candidates.size() == 1) {
            CompletionCandidate candidate = candidates.get(0);
            String completed = candidate.replacement;

            if (candidate.addSpace) {
                completed = completed + " ";
            }

            if (!completed.startsWith(context.currentWord)) {
                return;
            }

            String suffix = completed.substring(context.currentWord.length());
            buffer.replace(context.wordStart, buffer.length(), completed);
            System.out.print(suffix);
            System.out.flush();
            lastCompletionBuffer = buffer.toString();
            return;
        }

        List<String> replacements = new ArrayList<>();
        for (CompletionCandidate candidate : candidates) {
            replacements.add(candidate.replacement);
        }

        String commonPrefix = longestCommonPrefix(replacements);

        if (commonPrefix.length() > context.currentWord.length()) {
            String suffix = commonPrefix.substring(context.currentWord.length());
            buffer.replace(context.wordStart, buffer.length(), commonPrefix);
            System.out.print(suffix);
            System.out.flush();
            lastCompletionBuffer = buffer.toString();
            return;
        }

        if (repeatedTabCount >= 2) {
            System.out.println();
            printCompletionCandidates(candidates);
            System.out.print("$ " + buffer);
            System.out.flush();
        } else {
            bell();
        }
    }

    private static List<CompletionCandidate> getCommandCompletionCandidates(String prefix) {
        List<CompletionCandidate> candidates = new ArrayList<>();

        String[] builtins = {
                "echo", "exit", "type", "pwd", "cd", "jobs",
                "history", "complete", "declare"
        };

        for (String builtin : builtins) {
            if (builtin.startsWith(prefix)) {
                candidates.add(new CompletionCandidate(builtin, builtin, true));
            }
        }

        candidates.addAll(getExecutableCompletionCandidates(prefix));

        if (prefix.contains("/") || prefix.startsWith(".") || prefix.startsWith("~")) {
            candidates.addAll(getFileCompletionCandidates(prefix));
        }

        return candidates;
    }

    private static List<CompletionCandidate> getArgumentCompletionCandidates(CompletionContext context) {
        String command = context.commandName;

        if (command != null && completionSpecs.containsKey(command)) {
            List<CompletionCandidate> programmed = getProgrammableCompletions(context);
            if (!programmed.isEmpty()) {
                return programmed;
            }
            bell();
            return new ArrayList<>();
        }

        return getFileCompletionCandidates(context.currentWord);
    }

    private static List<CompletionCandidate> getExecutableCompletionCandidates(String prefix) {
        List<CompletionCandidate> candidates = new ArrayList<>();
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null || pathEnv.isEmpty()) {
            return candidates;
        }

        String[] directories = pathEnv.split(File.pathSeparator);
        Set<String> seen = new LinkedHashSet<>();

        for (String directory : directories) {
            File dir = new File(directory);
            File[] files = dir.listFiles();

            if (files == null) {
                continue;
            }

            for (File file : files) {
                String name = file.getName();

                if (!seen.contains(name) && name.startsWith(prefix) && file.isFile() && file.canExecute()) {
                    seen.add(name);
                    candidates.add(new CompletionCandidate(name, name, true));
                }
            }
        }

        return candidates;
    }

    private static List<CompletionCandidate> getFileCompletionCandidates(String word) {
        List<CompletionCandidate> candidates = new ArrayList<>();

        String expandedWord = word;
        boolean startsWithTilde = false;

        if (word.startsWith("~")) {
            startsWithTilde = true;
            expandedWord = System.getenv("HOME") + word.substring(1);
        }

        int slashIndex = expandedWord.lastIndexOf('/');
        String directoryPart;
        String visibleDirectoryPart;
        String namePrefix;
        File directory;

        if (slashIndex >= 0) {
            directoryPart = expandedWord.substring(0, slashIndex + 1);
            visibleDirectoryPart = word.substring(0, Math.min(word.length(), slashIndex + 1));
            namePrefix = expandedWord.substring(slashIndex + 1);
            directory = new File(directoryPart);
        } else {
            visibleDirectoryPart = "";
            namePrefix = expandedWord;
            directory = currentDirectory;
        }

        if (!directory.isAbsolute()) {
            directory = new File(currentDirectory, directory.getPath());
        }

        File[] files = directory.listFiles();

        if (files == null) {
            return candidates;
        }

        for (File file : files) {
            String name = file.getName();

            if (!name.startsWith(namePrefix)) {
                continue;
            }

            String replacement;

            if (startsWithTilde && slashIndex < 0) {
                replacement = "~" + name;
            } else {
                replacement = visibleDirectoryPart + name;
            }

            boolean addSpace = !file.isDirectory();

            if (file.isDirectory()) {
                replacement = replacement + "/";
            }

            candidates.add(new CompletionCandidate(name, replacement, addSpace));
        }

        return candidates;
    }

    private static List<CompletionCandidate> getProgrammableCompletions(CompletionContext context) {
        List<CompletionCandidate> candidates = new ArrayList<>();
        CompletionSpec spec = completionSpecs.get(context.commandName);

        if (spec == null) {
            return candidates;
        }

        List<String> words = new ArrayList<>();

        if (spec.type.equals("W")) {
            words.addAll(Arrays.asList(spec.value.split("\\s+")));
        } else if (spec.type.equals("C")) {
            words.addAll(runCompleterCommand(spec.value, context));
        }

        for (String word : words) {
            if (word.startsWith(context.currentWord)) {
                candidates.add(new CompletionCandidate(word, word, true));
            }
        }

        return candidates;
    }

    private static List<String> runCompleterCommand(String completer, CompletionContext context) {
        List<String> results = new ArrayList<>();

        try {
            List<String> command = new ArrayList<>();
            command.add(completer);
            command.add(context.commandName == null ? "" : context.commandName);
            command.add(context.currentWord == null ? "" : context.currentWord);
            command.add(context.previousWord == null ? "" : context.previousWord);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(currentDirectory);
            Map<String, String> env = pb.environment();
            env.put("COMP_LINE", context.fullLine);
            env.put("COMP_POINT", String.valueOf(context.fullLine.length()));
            env.put("COMP_WORDS", context.fullLine);
            env.put("COMP_CWORD", String.valueOf(context.wordIndex));

            Process process = pb.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            process.waitFor();

            String output = new String(stdout, StandardCharsets.UTF_8);
            for (String line : output.split("\\R")) {
                if (!line.isEmpty()) {
                    results.add(line.trim());
                }
            }
        } catch (Exception ignored) {
        }

        return results;
    }

    private static CompletionContext getCompletionContext(String line) {
        int wordStart = line.length();

        while (wordStart > 0 && !Character.isWhitespace(line.charAt(wordStart - 1))) {
            wordStart--;
        }

        String currentWord = line.substring(wordStart);
        String beforeCurrent = line.substring(0, wordStart);
        List<String> previousWords = simpleSplit(beforeCurrent.trim());
        boolean commandPosition = previousWords.isEmpty();
        String commandName = previousWords.isEmpty() ? currentWord : previousWords.get(0);
        String previousWord = previousWords.isEmpty() ? "" : previousWords.get(previousWords.size() - 1);
        int wordIndex = previousWords.size();

        return new CompletionContext(
                line,
                currentWord,
                wordStart,
                commandPosition,
                commandName,
                previousWord,
                wordIndex
        );
    }

    private static List<String> simpleSplit(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(text.split("\\s+")));
    }

    private static List<CompletionCandidate> uniqueAndSortedCandidates(List<CompletionCandidate> candidates) {
        Map<String, CompletionCandidate> map = new LinkedHashMap<>();

        for (CompletionCandidate candidate : candidates) {
            map.putIfAbsent(candidate.replacement, candidate);
        }

        List<CompletionCandidate> result = new ArrayList<>(map.values());
        result.sort(Comparator.comparing(candidate -> candidate.display));
        return result;
    }

    private static String longestCommonPrefix(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }

        String prefix = values.get(0);

        for (int i = 1; i < values.size(); i++) {
            String value = values.get(i);
            int length = Math.min(prefix.length(), value.length());
            int j = 0;

            while (j < length && prefix.charAt(j) == value.charAt(j)) {
                j++;
            }

            prefix = prefix.substring(0, j);

            if (prefix.isEmpty()) {
                break;
            }
        }

        return prefix;
    }

    private static void printCompletionCandidates(List<CompletionCandidate> candidates) {
        List<String> displays = new ArrayList<>();

        for (CompletionCandidate candidate : candidates) {
            displays.add(candidate.display);
        }

        Collections.sort(displays);
        System.out.println(String.join("  ", displays));
        System.out.flush();
    }

    private static void bell() {
        System.out.print("\u0007");
        System.out.flush();
    }

    private static void resetTabState() {
        lastCompletionBuffer = null;
        repeatedTabCount = 0;
    }

    private static void executeLine(String input) {
        String expandedInput = expandVariables(input);
        List<String> tokens = tokenize(expandedInput);

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
            saveHistoryOnExit();
            restoreTerminal();
            System.exit(0);
        }

        if (isBuiltin(command) && !background) {
            runBuiltin(parsedCommand, input);
            return;
        }

        if (isBuiltin(command)) {
            runBuiltinInBackground(parsedCommand, input);
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

    private static void runBuiltin(ParsedCommand parsedCommand, String originalInput) {
        List<String> commandParts = parsedCommand.commandParts;
        String command = commandParts.get(0);

        switch (command) {
            case "echo":
                createEmptyFileIfNeeded(parsedCommand.stderrFile, parsedCommand.stderrAppend);
                String output = "";
                if (commandParts.size() > 1) {
                    output = String.join(" ", commandParts.subList(1, commandParts.size()));
                }
                writeStdout(output, parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
                break;
            case "pwd":
                createEmptyFileIfNeeded(parsedCommand.stderrFile, parsedCommand.stderrAppend);
                writeStdout(currentDirectory.getAbsolutePath(), parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
                break;
            case "cd":
                handleCd(commandParts, parsedCommand.stderrFile, parsedCommand.stderrAppend);
                break;
            case "type":
                handleType(commandParts, parsedCommand.stdoutFile, parsedCommand.stdoutAppend, parsedCommand.stderrFile, parsedCommand.stderrAppend);
                break;
            case "jobs":
                reapCompletedJobs();
                handleJobs(parsedCommand.stdoutFile, parsedCommand.stdoutAppend);
                createEmptyFileIfNeeded(parsedCommand.stderrFile, parsedCommand.stderrAppend);
                break;
            case "history":
                handleHistory(commandParts, parsedCommand.stdoutFile, parsedCommand.stdoutAppend, parsedCommand.stderrFile, parsedCommand.stderrAppend);
                break;
            case "declare":
                handleDeclare(commandParts, parsedCommand.stdoutFile, parsedCommand.stdoutAppend, parsedCommand.stderrFile, parsedCommand.stderrAppend);
                break;
            case "complete":
                handleComplete(commandParts, parsedCommand.stdoutFile, parsedCommand.stdoutAppend, parsedCommand.stderrFile, parsedCommand.stderrAppend);
                break;
            default:
                break;
        }
    }

    private static void runBuiltinInBackground(ParsedCommand parsedCommand, String originalInput) {
        Thread worker = new Thread(() -> runBuiltin(parsedCommand, originalInput));
        worker.setDaemon(false);
        worker.start();

        int jobId = nextJobId();
        Job job = new Job(jobId, originalInput.trim(), new ArrayList<>());
        job.workerThread = worker;
        jobs.add(job);
        System.out.println("[" + jobId + "] " + worker.getId());
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
                jobs.add(new Job(jobId, originalInput.trim(), List.of(process)));
                System.out.println("[" + jobId + "] " + process.pid());
            } else {
                process.waitFor();
            }
        } catch (Exception e) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
        }
    }

    private static void runPipeline(ParsedCommand parsedCommand, boolean background, String originalInput) {
        if (background) {
            Thread worker = new Thread(() -> runPipeline(parsedCommand, false, originalInput));
            worker.setDaemon(false);
            worker.start();
            int jobId = nextJobId();
            Job job = new Job(jobId, originalInput.trim(), new ArrayList<>());
            job.workerThread = worker;
            jobs.add(job);
            System.out.println("[" + jobId + "] " + worker.getId());
            return;
        }

        List<List<String>> commands = splitPipeline(parsedCommand.commandParts);
        byte[] input = new byte[0];

        for (int i = 0; i < commands.size(); i++) {
            boolean last = i == commands.size() - 1;
            CommandResult result = executeForPipeline(
                    commands.get(i),
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
                String output = commandParts.size() > 1
                        ? String.join(" ", commandParts.subList(1, commandParts.size()))
                        : "";
                return new CommandResult((output + "\n").getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("pwd")) {
                return new CommandResult((currentDirectory.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("type")) {
                String output = getTypeOutput(commandParts);
                return new CommandResult((output + "\n").getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("jobs")) {
                return new CommandResult(getJobsOutput().getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("history")) {
                return new CommandResult(getHistoryOutput(commandParts).getBytes(StandardCharsets.UTF_8));
            }

            if (command.equals("declare") || command.equals("complete") || command.equals("cd") || command.equals("exit")) {
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
            } catch (Exception ignored) {
            }

            byte[] stdout = process.getInputStream().readAllBytes();
            process.waitFor();
            return new CommandResult(stdout);
        } catch (Exception e) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
            return new CommandResult(new byte[0]);
        }
    }

    private static void handleCd(List<String> commandParts, String stderrFile, boolean stderrAppend) {
        String path = commandParts.size() < 2 ? System.getenv("HOME") : commandParts.get(1);

        if (path == null || path.isEmpty()) {
            return;
        }

        File target;

        if (path.equals("~")) {
            target = new File(System.getenv("HOME"));
        } else if (path.startsWith("~/")) {
            target = new File(System.getenv("HOME"), path.substring(2));
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

        writeStdout(getTypeOutput(commandParts), stdoutFile, stdoutAppend);
    }

    private static String getTypeOutput(List<String> commandParts) {
        if (commandParts.size() < 2) {
            return "";
        }

        String target = commandParts.get(1);

        if (isBuiltin(target)) {
            return target + " is a shell builtin";
        }

        String executablePath = findExecutable(target);

        if (executablePath != null) {
            return target + " is " + executablePath;
        }

        return target + ": not found";
    }

    private static void handleJobs(String stdoutFile, boolean stdoutAppend) {
        String output = getJobsOutput();

        if (output.isEmpty()) {
            return;
        }

        if (output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }

        writeStdout(output, stdoutFile, stdoutAppend);
    }

    private static String getJobsOutput() {
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

        return output.toString();
    }

    private static void handleHistory(
            List<String> commandParts,
            String stdoutFile,
            boolean stdoutAppend,
            String stderrFile,
            boolean stderrAppend
    ) {
        if (commandParts.size() >= 3 && commandParts.get(1).equals("-r")) {
            readHistoryFromFile(commandParts.get(2));
            return;
        }

        if (commandParts.size() >= 3 && commandParts.get(1).equals("-w")) {
            writeHistoryToFile(commandParts.get(2), false);
            return;
        }

        if (commandParts.size() >= 3 && commandParts.get(1).equals("-a")) {
            writeHistoryToFile(commandParts.get(2), true);
            return;
        }

        writeStdoutWithoutExtraBlank(getHistoryOutput(commandParts), stdoutFile, stdoutAppend);
        createEmptyFileIfNeeded(stderrFile, stderrAppend);
    }

    private static String getHistoryOutput(List<String> commandParts) {
        int start = 0;

        if (commandParts.size() >= 2) {
            try {
                int limit = Integer.parseInt(commandParts.get(1));
                start = Math.max(0, history.size() - limit);
            } catch (Exception ignored) {
            }
        }

        StringBuilder output = new StringBuilder();

        for (int i = start; i < history.size(); i++) {
            output.append(String.format("%5d  %s", i + 1, history.get(i))).append("\n");
        }

        return output.toString();
    }

    private static void handleDeclare(
            List<String> commandParts,
            String stdoutFile,
            boolean stdoutAppend,
            String stderrFile,
            boolean stderrAppend
    ) {
        if (commandParts.size() == 1) {
            StringBuilder output = new StringBuilder();
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                output.append("declare -- ")
                        .append(entry.getKey())
                        .append("=\"")
                        .append(entry.getValue())
                        .append("\"\n");
            }
            writeStdoutWithoutExtraBlank(output.toString(), stdoutFile, stdoutAppend);
            return;
        }

        for (int i = 1; i < commandParts.size(); i++) {
            String argument = commandParts.get(i);
            int equalsIndex = argument.indexOf('=');

            if (equalsIndex >= 0) {
                String name = argument.substring(0, equalsIndex);
                String value = argument.substring(equalsIndex + 1);

                if (!isValidVariableName(name)) {
                    writeStderr("declare: `" + name + "': not a valid identifier", stderrFile, stderrAppend);
                    continue;
                }

                variables.put(name, value);
            } else {
                if (!variables.containsKey(argument)) {
                    writeStderr("declare: " + argument + ": not found", stderrFile, stderrAppend);
                } else {
                    writeStdout("declare -- " + argument + "=\"" + variables.get(argument) + "\"", stdoutFile, stdoutAppend);
                }
            }
        }
    }

    private static void handleComplete(
            List<String> commandParts,
            String stdoutFile,
            boolean stdoutAppend,
            String stderrFile,
            boolean stderrAppend
    ) {
        if (commandParts.size() == 1 || (commandParts.size() == 2 && commandParts.get(1).equals("-p"))) {
            StringBuilder output = new StringBuilder();
            List<String> names = new ArrayList<>(completionSpecs.keySet());
            Collections.sort(names);
            for (String name : names) {
                CompletionSpec spec = completionSpecs.get(name);
                output.append("complete -")
                        .append(spec.type)
                        .append(" ")
                        .append(spec.value)
                        .append(" ")
                        .append(name)
                        .append("\n");
            }
            writeStdoutWithoutExtraBlank(output.toString(), stdoutFile, stdoutAppend);
            return;
        }

        if (commandParts.size() >= 3 && commandParts.get(1).equals("-r")) {
            completionSpecs.remove(commandParts.get(2));
            return;
        }

        if (commandParts.size() >= 4 && commandParts.get(1).equals("-C")) {
            String completer = commandParts.get(2);
            String command = commandParts.get(3);
            completionSpecs.put(command, new CompletionSpec("C", completer));
            return;
        }

        if (commandParts.size() >= 4 && commandParts.get(1).equals("-W")) {
            String words = commandParts.get(2);
            String command = commandParts.get(3);
            completionSpecs.put(command, new CompletionSpec("W", words));
            return;
        }

        writeStderr("complete: missing completion specification", stderrFile, stderrAppend);
    }

    private static String expandHistoryCommand(String input) {
        if (!input.startsWith("!")) {
            return input;
        }

        if (input.length() == 1) {
            return input;
        }

        String value = input.substring(1);

        try {
            int index = Integer.parseInt(value) - 1;
            if (index >= 0 && index < history.size()) {
                return history.get(index);
            }
        } catch (Exception ignored) {
        }

        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).startsWith(value)) {
                return history.get(i);
            }
        }

        System.out.println(input + ": event not found");
        return null;
    }

    private static String expandVariables(String input) {
        StringBuilder output = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                output.append(c);
                continue;
            }

            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                output.append(c);
                continue;
            }

            if (c == '$' && !inSingleQuotes) {
                if (i + 1 < input.length() && input.charAt(i + 1) == '{') {
                    int end = input.indexOf('}', i + 2);
                    if (end != -1) {
                        String name = input.substring(i + 2, end);
                        output.append(getVariableValue(name));
                        i = end;
                        continue;
                    }
                }

                int j = i + 1;
                while (j < input.length()) {
                    char n = input.charAt(j);
                    if (Character.isLetterOrDigit(n) || n == '_') {
                        j++;
                    } else {
                        break;
                    }
                }

                if (j > i + 1) {
                    String name = input.substring(i + 1, j);
                    output.append(getVariableValue(name));
                    i = j - 1;
                    continue;
                }
            }

            output.append(c);
        }

        return output.toString();
    }

    private static String getVariableValue(String name) {
        if (variables.containsKey(name)) {
            return variables.get(name);
        }

        String env = System.getenv(name);
        return env == null ? "" : env;
    }

    private static boolean isValidVariableName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
            return false;
        }

        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }

        return true;
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
                    stdoutFile = tokens.get(++i);
                    stdoutAppend = false;
                }
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(++i);
                    stdoutAppend = true;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < tokens.size()) {
                    stderrFile = tokens.get(++i);
                    stderrAppend = false;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < tokens.size()) {
                    stderrFile = tokens.get(++i);
                    stderrAppend = true;
                }
            } else {
                commandParts.add(token);
            }
        }

        return new ParsedCommand(commandParts, stdoutFile, stdoutAppend, stderrFile, stderrAppend);
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
                    flushToken(tokens, current);
                    continue;
                }

                if (c == '|') {
                    flushToken(tokens, current);
                    tokens.add("|");
                    continue;
                }

                if (c == '&') {
                    flushToken(tokens, current);
                    tokens.add("&");
                    continue;
                }

                if (c == '1' && i + 2 < input.length() && input.charAt(i + 1) == '>' && input.charAt(i + 2) == '>') {
                    flushToken(tokens, current);
                    tokens.add("1>>");
                    i += 2;
                    continue;
                }

                if (c == '2' && i + 2 < input.length() && input.charAt(i + 1) == '>' && input.charAt(i + 2) == '>') {
                    flushToken(tokens, current);
                    tokens.add("2>>");
                    i += 2;
                    continue;
                }

                if (c == '1' && i + 1 < input.length() && input.charAt(i + 1) == '>') {
                    flushToken(tokens, current);
                    tokens.add("1>");
                    i++;
                    continue;
                }

                if (c == '2' && i + 1 < input.length() && input.charAt(i + 1) == '>') {
                    flushToken(tokens, current);
                    tokens.add("2>");
                    i++;
                    continue;
                }

                if (c == '>' && i + 1 < input.length() && input.charAt(i + 1) == '>') {
                    flushToken(tokens, current);
                    tokens.add(">>");
                    i++;
                    continue;
                }

                if (c == '>') {
                    flushToken(tokens, current);
                    tokens.add(">");
                    continue;
                }
            }

            current.append(c);
        }

        flushToken(tokens, current);
        return tokens;
    }

    private static void flushToken(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
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

    private static boolean isBuiltin(String command) {
        return command.equals("exit")
                || command.equals("echo")
                || command.equals("type")
                || command.equals("pwd")
                || command.equals("cd")
                || command.equals("jobs")
                || command.equals("history")
                || command.equals("complete")
                || command.equals("declare");
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

        for (String directory : pathEnv.split(File.pathSeparator)) {
            File file = new File(directory, command);

            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    private static void configureProcessOutput(ProcessBuilder pb, String stdoutFile, boolean stdoutAppend) {
        if (stdoutFile != null) {
            File file = resolveFile(stdoutFile);
            if (stdoutAppend) {
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
            } else {
                pb.redirectOutput(file);
            }
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
    }

    private static void configureProcessError(ProcessBuilder pb, String stderrFile, boolean stderrAppend) {
        if (stderrFile != null) {
            File file = resolveFile(stderrFile);
            if (stderrAppend) {
                pb.redirectError(ProcessBuilder.Redirect.appendTo(file));
            } else {
                pb.redirectError(file);
            }
        } else {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
    }

    private static void writeStdout(String text, String file, boolean append) {
        if (file == null) {
            System.out.println(text);
            System.out.flush();
        } else {
            writeToFile(text + "\n", file, append);
        }
    }

    private static void writeStdoutWithoutExtraBlank(String text, String file, boolean append) {
        if (text == null || text.isEmpty()) {
            return;
        }

        if (file == null) {
            System.out.print(text);
            System.out.flush();
        } else {
            writeToFile(text, file, append);
        }
    }

    private static void writeStderr(String text, String file, boolean append) {
        if (file == null) {
            System.out.println(text);
            System.out.flush();
        } else {
            writeToFile(text + "\n", file, append);
        }
    }

    private static void writeRawToFile(String text, String file, boolean append) {
        writeToFile(text, file, append);
    }

    private static void writeToFile(String text, String path, boolean append) {
        try {
            File file = resolveFile(path);
            try (FileOutputStream outputStream = new FileOutputStream(file, append)) {
                outputStream.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static void createEmptyFileIfNeeded(String path, boolean append) {
        if (path == null) {
            return;
        }

        try {
            File file = resolveFile(path);
            if (append && file.exists()) {
                return;
            }
            try (FileOutputStream ignored = new FileOutputStream(file, append)) {
            }
        } catch (Exception ignored) {
        }
    }

    private static File resolveFile(String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(currentDirectory, path);
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

    private static void loadHistoryOnStartup() {
        String historyFile = System.getenv("HISTFILE");
        if (historyFile != null && !historyFile.isEmpty()) {
            readHistoryFromFile(historyFile);
        }
    }

    private static void saveHistoryOnExit() {
        String historyFile = System.getenv("HISTFILE");
        if (historyFile != null && !historyFile.isEmpty()) {
            writeHistoryToFile(historyFile, false);
        }
    }

    private static void readHistoryFromFile(String path) {
        try {
            File file = resolveFile(path);
            if (file.exists()) {
                history.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static void writeHistoryToFile(String path, boolean append) {
        StringBuilder output = new StringBuilder();
        for (String item : history) {
            output.append(item).append("\n");
        }
        writeToFile(output.toString(), path, append);
    }

    private static void enableRawMode() {
        runStty("raw -echo min 1 time 0");
    }

    private static void restoreTerminal() {
        runStty("sane");
    }

    private static void runStty(String mode) {
        try {
            new ProcessBuilder("/bin/sh", "-c", "stty " + mode + " < /dev/tty")
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
        }
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

    private static class CompletionCandidate {
        String display;
        String replacement;
        boolean addSpace;

        CompletionCandidate(String display, String replacement, boolean addSpace) {
            this.display = display;
            this.replacement = replacement;
            this.addSpace = addSpace;
        }
    }

    private static class CompletionContext {
        String fullLine;
        String currentWord;
        int wordStart;
        boolean commandPosition;
        String commandName;
        String previousWord;
        int wordIndex;

        CompletionContext(
                String fullLine,
                String currentWord,
                int wordStart,
                boolean commandPosition,
                String commandName,
                String previousWord,
                int wordIndex
        ) {
            this.fullLine = fullLine;
            this.currentWord = currentWord;
            this.wordStart = wordStart;
            this.commandPosition = commandPosition;
            this.commandName = commandName;
            this.previousWord = previousWord;
            this.wordIndex = wordIndex;
        }
    }

    private static class CompletionSpec {
        String type;
        String value;

        CompletionSpec(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }
}
