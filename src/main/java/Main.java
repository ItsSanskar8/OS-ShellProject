import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    private static File currentDirectory = new File(System.getProperty("user.dir"));

    private static final List<Job> jobs = new ArrayList<>();
    private static final Map<String, String> completers = new HashMap<>();

    private static String lastCompletionToken = null;
    private static String lastCompletionLine = null;
    private static List<String> lastCompletionDisplays = new ArrayList<>();

    private static final List<String> BUILTINS = Arrays.asList(
            "echo", "exit", "type", "pwd", "cd", "jobs", "complete"
    );

    public static void main(String[] args) throws Exception {
        while (true) {
            reapCompletedJobs();

            System.out.print("$ ");
            System.out.flush();

            String input = readLineWithCompletion();

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

    private static String readLineWithCompletion() throws Exception {
        StringBuilder buffer = new StringBuilder();
        resetCompletionState();

        while (true) {
            int value = System.in.read();

            if (value == -1) {
                return null;
            }

            char c = (char) value;

            if (c == '\r' || c == '\n') {
                System.out.print("\r\n");
                System.out.flush();
                resetCompletionState();
                return buffer.toString();
            }

            if (c == '\t') {
                handleTabCompletion(buffer);
                continue;
            }

            if (c == 127 || c == 8) {
                resetCompletionState();

                if (buffer.length() > 0) {
                    buffer.deleteCharAt(buffer.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }

                continue;
            }

            if (c == 3) {
                resetCompletionState();
                buffer.setLength(0);
                System.out.print("\r\n");
                System.out.flush();
                return "";
            }

            resetCompletionState();
            buffer.append(c);
            System.out.print(c);
            System.out.flush();
        }
    }

    private static void handleTabCompletion(StringBuilder buffer) {
        String line = buffer.toString();

        CompletionContext context = getCompletionContext(line);
        List<TabCandidate> candidates;

        if (!context.commandPosition && completers.containsKey(context.commandName)) {
            candidates = getProgrammableCandidates(context);
        } else if (context.commandPosition) {
            candidates = getCommandCandidates(context.currentToken);
        } else {
            candidates = getPathCandidates(context.currentToken);
        }

        candidates.sort(Comparator.comparing(c -> c.display));

        if (candidates.isEmpty()) {
            bell();
            resetCompletionState();
            return;
        }

        if (candidates.size() == 1) {
            TabCandidate candidate = candidates.get(0);
            String suffix = candidate.replacement.substring(Math.min(context.currentToken.length(), candidate.replacement.length()));

            if (!candidate.replacement.startsWith(context.currentToken)) {
                suffix = candidate.replacement;
            }

            buffer.replace(context.tokenStart, buffer.length(), candidate.replacement);
            System.out.print(suffix);
            System.out.flush();

            resetCompletionState();
            return;
        }

        List<String> lcpValues = new ArrayList<>();
        for (TabCandidate candidate : candidates) {
            lcpValues.add(candidate.lcpValue);
        }

        String commonPrefix = longestCommonPrefix(lcpValues);

        if (commonPrefix.length() > context.currentToken.length()) {
            String suffix = commonPrefix.substring(context.currentToken.length());
            buffer.replace(context.tokenStart, buffer.length(), commonPrefix);
            System.out.print(suffix);
            System.out.flush();

            resetCompletionState();
            return;
        }

        List<String> displays = new ArrayList<>();
        for (TabCandidate candidate : candidates) {
            displays.add(candidate.display);
        }

        if (line.equals(lastCompletionLine)
                && context.currentToken.equals(lastCompletionToken)
                && displays.equals(lastCompletionDisplays)) {
            System.out.print("\r\n");
            System.out.print(String.join("  ", displays));
            System.out.print("\r\n");
            System.out.print("$ " + buffer);
            System.out.flush();
            return;
        }

        lastCompletionLine = line;
        lastCompletionToken = context.currentToken;
        lastCompletionDisplays = new ArrayList<>(displays);

        bell();
    }

    private static CompletionContext getCompletionContext(String line) {
        int tokenStart = line.length();

        while (tokenStart > 0 && !Character.isWhitespace(line.charAt(tokenStart - 1))) {
            tokenStart--;
        }

        String currentToken = line.substring(tokenStart);
        String beforeToken = line.substring(0, tokenStart);
        List<String> previousWords = splitWords(beforeToken.trim());

        boolean commandPosition = previousWords.isEmpty();
        String commandName = commandPosition ? currentToken : previousWords.get(0);

        String previousWord = "";
        if (!commandPosition && previousWords.size() >= 2) {
            previousWord = previousWords.get(previousWords.size() - 1);
        }

        return new CompletionContext(line, currentToken, tokenStart, commandPosition, commandName, previousWord);
    }

    private static List<String> splitWords(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(text.split("\\s+")));
    }

    private static List<TabCandidate> getCommandCandidates(String prefix) {
        List<TabCandidate> candidates = new ArrayList<>();

        for (String builtin : BUILTINS) {
            if (builtin.startsWith(prefix)) {
                candidates.add(new TabCandidate(builtin, builtin, builtin + " "));
            }
        }

        // CodeCrafters expects builtins to win over PATH executables.
        if (!candidates.isEmpty()) {
            return candidates;
        }

        String path = System.getenv("PATH");

        if (path == null || path.isEmpty()) {
            return candidates;
        }

        Set<String> seen = new HashSet<>();

        for (String dirName : path.split(File.pathSeparator)) {
            File dir = new File(dirName);
            File[] files = dir.listFiles();

            if (files == null) {
                continue;
            }

            for (File file : files) {
                String name = file.getName();

                if (name.startsWith(prefix) && file.isFile() && file.canExecute() && !seen.contains(name)) {
                    seen.add(name);
                    candidates.add(new TabCandidate(name, name, name + " "));
                }
            }
        }

        return candidates;
    }

    private static List<TabCandidate> getPathCandidates(String token) {
        List<TabCandidate> candidates = new ArrayList<>();

        int slashIndex = token.lastIndexOf('/');

        String dirPart;
        String prefix;

        if (slashIndex >= 0) {
            dirPart = token.substring(0, slashIndex + 1);
            prefix = token.substring(slashIndex + 1);
        } else {
            dirPart = "";
            prefix = token;
        }

        File searchDir;

        if (dirPart.isEmpty()) {
            searchDir = currentDirectory;
        } else {
            File given = new File(dirPart);
            searchDir = given.isAbsolute() ? given : new File(currentDirectory, dirPart);
        }

        File[] entries = searchDir.listFiles();

        if (entries == null) {
            return candidates;
        }

        Set<String> seen = new HashSet<>();

        for (File entry : entries) {
            String name = entry.getName();

            if (!name.startsWith(prefix)) {
                continue;
            }

            String base = dirPart + name;

            if (seen.contains(base)) {
                continue;
            }
            seen.add(base);

            if (entry.isDirectory()) {
                candidates.add(new TabCandidate(base + "/", base, base + "/"));
            } else {
                candidates.add(new TabCandidate(base, base, base + " "));
            }
        }

        return candidates;
    }

    private static List<TabCandidate> getProgrammableCandidates(CompletionContext context) {
        List<TabCandidate> candidates = new ArrayList<>();
        String script = completers.get(context.commandName);

        if (script == null) {
            return candidates;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    script,
                    context.commandName,
                    context.currentToken,
                    context.previousWord
            );

            pb.directory(currentDirectory);

            Map<String, String> env = pb.environment();
            env.put("COMP_LINE", context.fullLine);
            env.put("COMP_POINT", String.valueOf(context.fullLine.getBytes(StandardCharsets.UTF_8).length));

            Process process = pb.start();

            byte[] stdout = process.getInputStream().readAllBytes();
            process.waitFor();

            String output = new String(stdout, StandardCharsets.UTF_8);
            Set<String> seen = new HashSet<>();

            for (String line : output.split("\\R")) {
                String candidate = line.trim();

                if (candidate.isEmpty()) {
                    continue;
                }

                if (!context.currentToken.isEmpty() && !candidate.startsWith(context.currentToken)) {
                    continue;
                }

                if (seen.contains(candidate)) {
                    continue;
                }
                seen.add(candidate);

                candidates.add(new TabCandidate(candidate, candidate, candidate + " "));
            }
        } catch (Exception ignored) {
        }

        return candidates;
    }

    private static String longestCommonPrefix(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }

        String prefix = values.get(0);

        for (int i = 1; i < values.size(); i++) {
            String value = values.get(i);
            int limit = Math.min(prefix.length(), value.length());
            int j = 0;

            while (j < limit && prefix.charAt(j) == value.charAt(j)) {
                j++;
            }

            prefix = prefix.substring(0, j);

            if (prefix.isEmpty()) {
                break;
            }
        }

        return prefix;
    }

    private static void resetCompletionState() {
        lastCompletionToken = null;
        lastCompletionLine = null;
        lastCompletionDisplays.clear();
    }

    private static void bell() {
        System.out.print("\u0007");
        System.out.flush();
    }

    private static void executeLine(String input) {
        List<String> tokens = parseCommand(input);

        if (tokens.isEmpty()) {
            return;
        }

        boolean background = false;

        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
            background = true;
            tokens.remove(tokens.size() - 1);
        }

        ParsedCommand parsed = parseRedirections(tokens);

        if (parsed.parts.isEmpty()) {
            return;
        }

        if (containsPipe(parsed.parts)) {
            runPipeline(parsed, background, input);
            return;
        }

        String command = parsed.parts.get(0);

        if (!background && command.equals("exit")) {
            System.exit(0);
        }

        if (isBuiltin(command) && !background) {
            runBuiltin(parsed);
            return;
        }

        runExternalCommand(parsed.parts, parsed.stdoutFile, parsed.stdoutAppend,
                parsed.stderrFile, parsed.stderrAppend, background, input);
    }

    private static void runBuiltin(ParsedCommand parsed) {
        List<String> parts = parsed.parts;
        String command = parts.get(0);

        switch (command) {
            case "echo":
                createEmptyFileIfNeeded(parsed.stderrFile, parsed.stderrAppend);
                String output = parts.size() > 1 ? String.join(" ", parts.subList(1, parts.size())) : "";
                writeStdout(output, parsed.stdoutFile, parsed.stdoutAppend);
                break;

            case "pwd":
                createEmptyFileIfNeeded(parsed.stderrFile, parsed.stderrAppend);
                writeStdout(currentDirectory.getAbsolutePath(), parsed.stdoutFile, parsed.stdoutAppend);
                break;

            case "cd":
                handleCd(parts, parsed.stderrFile, parsed.stderrAppend);
                break;

            case "type":
                createEmptyFileIfNeeded(parsed.stderrFile, parsed.stderrAppend);
                handleType(parts, parsed.stdoutFile, parsed.stdoutAppend);
                break;

            case "jobs":
                reapCompletedJobs();
                handleJobs(parsed.stdoutFile, parsed.stdoutAppend);
                createEmptyFileIfNeeded(parsed.stderrFile, parsed.stderrAppend);
                break;

            case "complete":
                handleComplete(parts, parsed.stdoutFile, parsed.stdoutAppend);
                createEmptyFileIfNeeded(parsed.stderrFile, parsed.stderrAppend);
                break;

            default:
                break;
        }
    }

    private static void handleComplete(List<String> parts, String stdoutFile, boolean stdoutAppend) {
        if (parts.size() >= 2 && parts.get(1).equals("-p")) {
            if (parts.size() == 2) {
                List<String> names = new ArrayList<>(completers.keySet());
                Collections.sort(names);
                StringBuilder output = new StringBuilder();

                for (String name : names) {
                    output.append("complete -C '").append(completers.get(name)).append("' ").append(name).append("\n");
                }

                String text = output.toString();
                if (text.endsWith("\n")) {
                    text = text.substring(0, text.length() - 1);
                }

                if (!text.isEmpty()) {
                    writeStdout(text, stdoutFile, stdoutAppend);
                }
                return;
            }

            String command = parts.get(2);
            String script = completers.get(command);

            if (script == null) {
                writeStdout("complete: " + command + ": no completion specification", stdoutFile, stdoutAppend);
            } else {
                writeStdout("complete -C '" + script + "' " + command, stdoutFile, stdoutAppend);
            }

            return;
        }

        if (parts.size() >= 3 && parts.get(1).equals("-r")) {
            completers.remove(parts.get(2));
            return;
        }

        if (parts.size() >= 4 && parts.get(1).equals("-C")) {
            String script = parts.get(2);
            String command = parts.get(3);
            completers.put(command, script);
        }
    }

    private static void handleCd(List<String> parts, String stderrFile, boolean stderrAppend) {
        String path = parts.size() < 2 ? System.getenv("HOME") : parts.get(1);

        if (path == null || path.isEmpty()) {
            return;
        }

        File target;

        if (path.equals("~")) {
            target = new File(System.getenv("HOME"));
        } else if (path.startsWith("~/")) {
            target = new File(System.getenv("HOME"), path.substring(2));
        } else {
            File candidate = new File(path);
            target = candidate.isAbsolute() ? candidate : new File(currentDirectory, path);
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

    private static void handleType(List<String> parts, String stdoutFile, boolean stdoutAppend) {
        if (parts.size() < 2) {
            return;
        }

        String target = parts.get(1);

        if (isBuiltin(target)) {
            writeStdout(target + " is a shell builtin", stdoutFile, stdoutAppend);
            return;
        }

        String path = findExecutable(target);

        if (path != null) {
            writeStdout(target + " is " + path, stdoutFile, stdoutAppend);
        } else {
            writeStdout(target + ": not found", stdoutFile, stdoutAppend);
        }
    }

    private static void handleJobs(String stdoutFile, boolean stdoutAppend) {
        StringBuilder sb = new StringBuilder();

        for (Job job : new ArrayList<>(jobs)) {
            if (job.isRunning()) {
                sb.append("[").append(job.id).append("] Running ").append(job.command).append("\n");
            }
        }

        String output = sb.toString();

        if (output.isEmpty()) {
            return;
        }

        if (output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }

        writeStdout(output, stdoutFile, stdoutAppend);
    }

    private static boolean isBuiltin(String command) {
        return BUILTINS.contains(command);
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

        String path = System.getenv("PATH");

        if (path == null || path.isEmpty()) {
            return null;
        }

        for (String dirName : path.split(File.pathSeparator)) {
            File file = new File(dirName, command);

            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    private static void runExternalCommand(List<String> parts, String stdoutFile, boolean stdoutAppend,
                                           String stderrFile, boolean stderrAppend,
                                           boolean background, String originalInput) {
        String command = parts.get(0);

        if (findExecutable(command) == null) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
            createEmptyFileIfNeeded(stdoutFile, stdoutAppend);
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.directory(currentDirectory);
            configureOutput(pb, stdoutFile, stdoutAppend);
            configureError(pb, stderrFile, stderrAppend);

            Process process = pb.start();

            if (background) {
                int jobId = nextJobId();
                jobs.add(new Job(jobId, originalInput.trim(), List.of(process)));
                printLine("[" + jobId + "] " + process.pid());
            } else {
                process.waitFor();
            }
        } catch (Exception e) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
        }
    }

    private static void runPipeline(ParsedCommand parsed, boolean background, String originalInput) {
        if (background) {
            Thread worker = new Thread(() -> runPipeline(parsed, false, originalInput));
            worker.setDaemon(false);
            worker.start();

            int jobId = nextJobId();
            Job job = new Job(jobId, originalInput.trim(), new ArrayList<>());
            job.workerThread = worker;
            jobs.add(job);

            printLine("[" + jobId + "] " + worker.getId());
            return;
        }

        List<List<String>> commands = splitPipeline(parsed.parts);

        if (allExternalCommands(commands)) {
            runStreamingExternalPipeline(commands, parsed);
            return;
        }

        byte[] input = new byte[0];

        for (int i = 0; i < commands.size(); i++) {
            boolean last = i == commands.size() - 1;
            input = executePipelineCommand(commands.get(i), input,
                    last ? parsed.stderrFile : null,
                    last && parsed.stderrAppend);
        }

        String output = new String(input, StandardCharsets.UTF_8);

        if (parsed.stdoutFile != null) {
            writeRawToFile(output, parsed.stdoutFile, parsed.stdoutAppend);
        } else {
            System.out.print(output);
            System.out.flush();
        }
    }

    private static boolean allExternalCommands(List<List<String>> commands) {
        for (List<String> commandParts : commands) {
            if (commandParts.isEmpty()) {
                return false;
            }

            if (isBuiltin(commandParts.get(0))) {
                return false;
            }
        }

        return true;
    }

    private static void runStreamingExternalPipeline(List<List<String>> commands, ParsedCommand parsed) {
        try {
            List<ProcessBuilder> builders = new ArrayList<>();

            for (List<String> commandParts : commands) {
                String command = commandParts.get(0);

                if (findExecutable(command) == null) {
                    writeStderr(command + ": command not found", parsed.stderrFile, parsed.stderrAppend);
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(commandParts);
                pb.directory(currentDirectory);

                if (parsed.stderrFile != null) {
                    File errFile = resolveFile(parsed.stderrFile);
                    pb.redirectError(parsed.stderrAppend
                            ? ProcessBuilder.Redirect.appendTo(errFile)
                            : ProcessBuilder.Redirect.to(errFile));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                builders.add(pb);
            }

            ProcessBuilder lastBuilder = builders.get(builders.size() - 1);

            if (parsed.stdoutFile != null) {
                File outFile = resolveFile(parsed.stdoutFile);
                lastBuilder.redirectOutput(parsed.stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(outFile)
                        : ProcessBuilder.Redirect.to(outFile));
            } else {
                lastBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            List<Process> processes = ProcessBuilder.startPipeline(builders);

            Process lastProcess = processes.get(processes.size() - 1);
            lastProcess.waitFor();

            for (int i = 0; i < processes.size() - 1; i++) {
                Process process = processes.get(i);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] executePipelineCommand(List<String> parts, byte[] stdin,
                                                 String stderrFile, boolean stderrAppend) {
        if (parts.isEmpty()) {
            return new byte[0];
        }

        String command = parts.get(0);

        try {
            if (command.equals("echo")) {
                String output = parts.size() > 1 ? String.join(" ", parts.subList(1, parts.size())) : "";
                return (output + "\n").getBytes(StandardCharsets.UTF_8);
            }

            if (command.equals("pwd")) {
                return (currentDirectory.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8);
            }

            if (command.equals("type")) {
                if (parts.size() < 2) {
                    return new byte[0];
                }

                String target = parts.get(1);
                String output;

                if (isBuiltin(target)) {
                    output = target + " is a shell builtin";
                } else {
                    String path = findExecutable(target);
                    output = path == null ? target + ": not found" : target + " is " + path;
                }

                return (output + "\n").getBytes(StandardCharsets.UTF_8);
            }

            if (findExecutable(command) == null) {
                writeStderr(command + ": command not found", stderrFile, stderrAppend);
                return new byte[0];
            }

            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.directory(currentDirectory);
            configureError(pb, stderrFile, stderrAppend);

            Process process = pb.start();

            try (OutputStream os = process.getOutputStream()) {
                os.write(stdin);
                os.flush();
            } catch (Exception ignored) {
            }

            byte[] stdout = process.getInputStream().readAllBytes();
            process.waitFor();

            return stdout;
        } catch (Exception e) {
            writeStderr(command + ": command not found", stderrFile, stderrAppend);
            return new byte[0];
        }
    }

    private static List<String> parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaping = false;
        boolean tokenStarted = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                if (inDoubleQuotes && ch != '"' && ch != '\\') {
                    current.append('\\');
                }
                current.append(ch);
                tokenStarted = true;
                escaping = false;
            } else if (ch == '\\' && !inSingleQuotes) {
                escaping = true;
                tokenStarted = true;
            } else if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                tokenStarted = true;
            } else if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                tokenStarted = true;
            } else if (Character.isWhitespace(ch) && !inSingleQuotes && !inDoubleQuotes) {
                if (tokenStarted) {
                    args.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else if (ch == '|' && !inSingleQuotes && !inDoubleQuotes) {
                if (tokenStarted) {
                    args.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                args.add("|");
            } else if (ch == '&' && !inSingleQuotes && !inDoubleQuotes) {
                if (tokenStarted) {
                    args.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                args.add("&");
            } else if (ch == '>' && !inSingleQuotes && !inDoubleQuotes) {
                if (tokenStarted) {
                    String token = current.toString();
                    if ((token.equals("1") || token.equals("2")) && i + 1 < input.length() && input.charAt(i + 1) == '>') {
                        args.add(token + ">>");
                        i++;
                    } else if (token.equals("1") || token.equals("2")) {
                        args.add(token + ">");
                    } else {
                        args.add(token);
                        if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                            args.add(">>");
                            i++;
                        } else {
                            args.add(">");
                        }
                    }
                    current.setLength(0);
                    tokenStarted = false;
                } else {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                        args.add(">>");
                        i++;
                    } else {
                        args.add(">");
                    }
                }
            } else {
                current.append(ch);
                tokenStarted = true;
            }
        }

        if (escaping) {
            current.append('\\');
            tokenStarted = true;
        }

        if (tokenStarted) {
            args.add(current.toString());
        }

        return args;
    }

    private static ParsedCommand parseRedirections(List<String> parts) {
        List<String> commandParts = new ArrayList<>();
        String stdoutFile = null;
        boolean stdoutAppend = false;
        String stderrFile = null;
        boolean stderrAppend = false;

        for (int i = 0; i < parts.size(); i++) {
            String token = parts.get(i);

            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < parts.size()) {
                    stdoutFile = parts.get(++i);
                    stdoutAppend = false;
                }
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < parts.size()) {
                    stdoutFile = parts.get(++i);
                    stdoutAppend = true;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < parts.size()) {
                    stderrFile = parts.get(++i);
                    stderrAppend = false;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < parts.size()) {
                    stderrFile = parts.get(++i);
                    stderrAppend = true;
                }
            } else {
                commandParts.add(token);
            }
        }

        return new ParsedCommand(commandParts, stdoutFile, stdoutAppend, stderrFile, stderrAppend);
    }

    private static boolean containsPipe(List<String> parts) {
        for (String part : parts) {
            if (part.equals("|")) {
                return true;
            }
        }
        return false;
    }

    private static List<List<String>> splitPipeline(List<String> parts) {
        List<List<String>> commands = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String part : parts) {
            if (part.equals("|")) {
                if (!current.isEmpty()) {
                    commands.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(part);
            }
        }

        if (!current.isEmpty()) {
            commands.add(current);
        }

        return commands;
    }

    private static void configureOutput(ProcessBuilder pb, String file, boolean append) {
        if (file == null) {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            return;
        }

        File resolved = resolveFile(file);
        pb.redirectOutput(append ? ProcessBuilder.Redirect.appendTo(resolved) : ProcessBuilder.Redirect.to(resolved));
    }

    private static void configureError(ProcessBuilder pb, String file, boolean append) {
        if (file == null) {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            return;
        }

        File resolved = resolveFile(file);
        pb.redirectError(append ? ProcessBuilder.Redirect.appendTo(resolved) : ProcessBuilder.Redirect.to(resolved));
    }

    private static void writeStdout(String text, String file, boolean append) {
        if (file == null) {
            printLine(text);
        } else {
            writeToFile(text + "\n", file, append);
        }
    }

    private static void writeStderr(String text, String file, boolean append) {
        if (file == null) {
            printLine(text);
        } else {
            writeToFile(text + "\n", file, append);
        }
    }

    private static void printLine(String text) {
        System.out.print(text + "\r\n");
        System.out.flush();
    }

    private static void writeRawToFile(String text, String file, boolean append) {
        writeToFile(text, file, append);
    }

    private static void writeToFile(String text, String path, boolean append) {
        try {
            File file = resolveFile(path);
            try (FileOutputStream output = new FileOutputStream(file, append)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
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

    private static class CompletionContext {
        String fullLine;
        String currentToken;
        int tokenStart;
        boolean commandPosition;
        String commandName;
        String previousWord;

        CompletionContext(String fullLine, String currentToken, int tokenStart,
                          boolean commandPosition, String commandName, String previousWord) {
            this.fullLine = fullLine;
            this.currentToken = currentToken;
            this.tokenStart = tokenStart;
            this.commandPosition = commandPosition;
            this.commandName = commandName;
            this.previousWord = previousWord;
        }
    }

    private static class TabCandidate {
        String display;
        String lcpValue;
        String replacement;

        TabCandidate(String display, String lcpValue, String replacement) {
            this.display = display;
            this.lcpValue = lcpValue;
            this.replacement = replacement;
        }
    }

    private static class ParsedCommand {
        List<String> parts;
        String stdoutFile;
        boolean stdoutAppend;
        String stderrFile;
        boolean stderrAppend;

        ParsedCommand(List<String> parts, String stdoutFile, boolean stdoutAppend,
                      String stderrFile, boolean stderrAppend) {
            this.parts = parts;
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
}
