import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static class JobInfo {

        int jobNumber;
        Process process;
        String command;

        JobInfo(int jobNumber, Process process, String command) {
            this.jobNumber = jobNumber;
            this.process = process;
            this.command = command;
        }
    }

    private static class RedirectInfo {

        List<String> parts;
        String stdoutFile;
        boolean stdoutAppend;
        String stderrFile;
        boolean stderrAppend;

        RedirectInfo(List<String> parts, String stdoutFile, boolean stdoutAppend,
                String stderrFile, boolean stderrAppend) {
            this.parts = parts;
            this.stdoutFile = stdoutFile;
            this.stdoutAppend = stdoutAppend;
            this.stderrFile = stderrFile;
            this.stderrAppend = stderrAppend;
        }
    }

    private static List<JobInfo> backgroundJobs = new ArrayList<>();

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
                if (inDoubleQuotes) {
                    if (ch == '"' || ch == '\\') {
                        current.append(ch);
                    } else {
                        current.append('\\');
                        current.append(ch);
                    }
                } else {
                    current.append(ch);
                }
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
            } else if (ch == '>' && !inSingleQuotes && !inDoubleQuotes) {
                if (tokenStarted) {
                    String token = current.toString();

                    if ((token.equals("1") || token.equals("2"))
                            && i + 1 < input.length()
                            && input.charAt(i + 1) == '>') {
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

    private static RedirectInfo extractRedirections(List<String> parts) {
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

        return new RedirectInfo(
                commandParts,
                stdoutFile,
                stdoutAppend,
                stderrFile,
                stderrAppend
        );
    }

    private static File findExecutable(String command) {
        String path = System.getenv("PATH");

        if (path == null) {
            return null;
        }

        String[] dirs = path.split(File.pathSeparator);

        for (String dir : dirs) {
            File file = new File(dir, command);

            if (file.exists() && file.canExecute()) {
                return file;
            }
        }

        return null;
    }

    private static void writeLineToFile(String fileName,
            String line,
            boolean append) throws Exception {
        try (PrintWriter writer
                = new PrintWriter(new FileOutputStream(fileName, append))) {
            writer.println(line);
        }
    }

    private static void touchFile(String fileName,
            boolean append) throws Exception {
        try (FileOutputStream ignored
                = new FileOutputStream(fileName, append)) {
            // create/truncate file only
        }
    }

    private static List<List<String>> splitPipelineParts(List<String> parts) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String token : parts) {
            if (token.equals("|")) {
                segments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }

        segments.add(current);
        return segments;
    }

    private static void runPipeline(List<List<String>> segments, File currentDir)
            throws Exception {
        if (segments.size() < 2) {
            return;
        }

        for (List<String> segment : segments) {
            if (segment.isEmpty()) {
                System.out.println("command not found");
                return;
            }

            String cmd = segment.get(0);
            if (!isBuiltin(cmd) && findExecutable(cmd) == null) {
                System.out.println(cmd + ": command not found");
                return;
            }
        }

        List<PipelineProcess> processes = new ArrayList<>();
        List<Thread> pumpThreads = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            List<String> segment = segments.get(i);
            boolean isLast = (i == segments.size() - 1);

            if (isBuiltin(segment.get(0))) {
                processes.add(new BuiltinPipelineProcess(segment, currentDir, isLast));
            } else {
                ProcessBuilder pb = new ProcessBuilder(segment);
                pb.directory(currentDir);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                if (isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                Process process = pb.start();
                processes.add(new ExternalPipelineProcess(process));
            }
        }

        for (int i = 0; i < processes.size() - 1; i++) {
            PipelineProcess left = processes.get(i);
            PipelineProcess right = processes.get(i + 1);

            Thread pumpThread = new Thread(() -> {
                try (InputStream in = left.getInputStream(); OutputStream out = right.getOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                } catch (IOException ignored) {
                }
            });

            pumpThread.start();
            pumpThreads.add(pumpThread);
        }

        for (PipelineProcess process : processes) {
            process.waitFor();
        }

        for (Thread t : pumpThreads) {
            t.join();
        }
    }

    private static boolean isBuiltin(String command) {
        return Set.of("exit", "echo", "type", "pwd", "cd", "jobs").contains(command);
    }

    private interface PipelineProcess {
        InputStream getInputStream();
        OutputStream getOutputStream();
        void waitFor() throws Exception;
    }

    private static class ExternalPipelineProcess implements PipelineProcess {
        private final Process process;
        ExternalPipelineProcess(Process process) {
            this.process = process;
        }
        public InputStream getInputStream() { return process.getInputStream(); }
        public OutputStream getOutputStream() { return process.getOutputStream(); }
        public void waitFor() throws Exception { process.waitFor(); }
    }

    private static class BuiltinPipelineProcess implements PipelineProcess {
        private final Thread thread;
        private final PipedInputStream stdinIn;
        private final PipedOutputStream stdinOut;
        private final PipedOutputStream stdoutOut;
        private final PipedInputStream stdoutIn;

        BuiltinPipelineProcess(List<String> parts, File currentDir, boolean isLast) throws IOException {
            this.stdinIn = new PipedInputStream();
            this.stdinOut = new PipedOutputStream(this.stdinIn);

            if (isLast) {
                this.stdoutOut = null;
                this.stdoutIn = null;
            } else {
                this.stdoutOut = new PipedOutputStream();
                this.stdoutIn = new PipedInputStream(this.stdoutOut);
            }

            this.thread = new Thread(() -> {
                try {
                    OutputStream out = isLast ? System.out : this.stdoutOut;
                    executeBuiltinCommand(parts, this.stdinIn, out, System.err, currentDir);
                } catch (Exception ignored) {
                } finally {
                    if (this.stdoutOut != null) {
                        try {
                            this.stdoutOut.close();
                        } catch (IOException ignored) {}
                    }
                    try {
                        this.stdinIn.close();
                    } catch (IOException ignored) {}
                    try {
                        this.stdinOut.close();
                    } catch (IOException ignored) {}
                }
            });
            this.thread.start();
        }

        public InputStream getInputStream() { return stdoutIn; }
        public OutputStream getOutputStream() { return stdinOut; }
        public void waitFor() throws Exception {
            try {
                thread.join();
            } catch (InterruptedException ignored) {}
        }
    }

    private static void executeBuiltinCommand(List<String> parts, InputStream in, OutputStream out, OutputStream err, File currentDir) throws Exception {
        String command = parts.get(0);
        PrintStream writer = new PrintStream(out, true);
        PrintStream errWriter = new PrintStream(err, true);

        if (command.equals("echo")) {
            String output = String.join(" ", parts.subList(1, parts.size()));
            writer.println(output);
        } else if (command.equals("type")) {
            if (parts.size() < 2) {
                writer.println("type: not found");
            } else {
                String arg = parts.get(1);
                Set<String> builtins = Set.of("exit", "echo", "type", "pwd", "cd", "jobs");
                if (builtins.contains(arg)) {
                    writer.println(arg + " is a shell builtin");
                } else {
                    File executable = findExecutable(arg);
                    if (executable != null) {
                        writer.println(arg + " is " + executable.getAbsolutePath());
                    } else {
                        writer.println(arg + ": not found");
                    }
                }
            }
        } else if (command.equals("pwd")) {
            writer.println(currentDir.getAbsolutePath());
        } else if (command.equals("cd")) {
            String arg = parts.size() > 1 ? parts.get(1) : "";
            File target;
            if (arg.equals("~")) {
                String home = System.getenv("HOME");
                target = home != null ? new File(home) : new File("null");
            } else {
                target = new File(arg);
                if (!target.isAbsolute()) {
                    target = new File(currentDir, arg);
                }
            }
            target = target.toPath().normalize().toAbsolutePath().toFile();
            if (!target.exists() || !target.isDirectory()) {
                errWriter.println("cd: " + arg + ": No such file or directory");
            }
        } else if (command.equals("jobs")) {
            checkAndPrintJobs(writer, true);
        }
    }

    private static String displayCommandWithoutTrailingAmpersand(String command) {
        if (command.endsWith(" &")) {
            return command.substring(0, command.length() - 2);
        }
        return command;
    }

    private static void checkAndPrintJobs(PrintStream out, boolean printAll) {
        int size = backgroundJobs.size();
        List<JobInfo> toRemove = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            JobInfo job = backgroundJobs.get(i);
            boolean isAlive = job.process.isAlive();

            char marker = ' ';
            if (i == size - 1) {
                marker = '+';
            } else if (i == size - 2) {
                marker = '-';
            }

            if (!isAlive) {
                out.printf(
                        "[%d]%c  %-24s%s%n",
                        job.jobNumber,
                        marker,
                        "Done",
                        displayCommandWithoutTrailingAmpersand(job.command)
                );
                toRemove.add(job);
            } else if (printAll) {
                out.printf(
                        "[%d]%c  %-24s%s%n",
                        job.jobNumber,
                        marker,
                        "Running",
                        job.command
                );
            }
        }

        backgroundJobs.removeAll(toRemove);
    }

    private static int getNextJobNumber() {
        int candidate = 1;
        while (true) {
            boolean found = false;
            for (JobInfo job : backgroundJobs) {
                if (job.jobNumber == candidate) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return candidate;
            }
            candidate++;
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        Set<String> builtins = Set.of(
                "exit",
                "echo",
                "type",
                "pwd",
                "cd",
                "jobs"
        );

        File currentDir
                = new File(System.getProperty("user.dir"))
                        .getAbsoluteFile();

        while (true) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
            checkAndPrintJobs(System.out, false);

            System.out.print("$ ");
            System.out.flush();

            String cmd = sc.nextLine();

            List<String> parts = parseCommand(cmd);

            if (parts.isEmpty()) {
                continue;
            }

            boolean background = false;
            if (parts.get(parts.size() - 1).equals("&")) {
                background = true;
                parts = new ArrayList<>(parts.subList(0, parts.size() - 1));
            }

            if (parts.isEmpty()) {
                continue;
            }

            if (parts.contains("|")) {
                List<List<String>> pipelineSegments = splitPipelineParts(parts);
                runPipeline(pipelineSegments, currentDir);
                continue;
            }

            RedirectInfo redirectInfo
                    = extractRedirections(parts);

            parts = redirectInfo.parts;

            if (parts.isEmpty()) {
                continue;
            }

            if (redirectInfo.stdoutFile != null) {
                touchFile(
                        redirectInfo.stdoutFile,
                        redirectInfo.stdoutAppend
                );
            }

            if (redirectInfo.stderrFile != null) {
                touchFile(
                        redirectInfo.stderrFile,
                        redirectInfo.stderrAppend
                );
            }

            if (parts.isEmpty()) {
                continue;
            }

            String command = parts.get(0);

            if (command.equals("exit")) {
                break;
            }

            if (command.equals("echo")) {
                String output
                        = String.join(" ",
                                parts.subList(1, parts.size()));

                if (redirectInfo.stdoutFile != null) {
                    writeLineToFile(
                            redirectInfo.stdoutFile,
                            output,
                            redirectInfo.stdoutAppend
                    );
                } else {
                    System.out.println(output);
                }
            } else if (command.equals("type")) {
                if (parts.size() < 2) {
                    String output = "type: not found";

                    if (redirectInfo.stdoutFile != null) {
                        writeLineToFile(
                                redirectInfo.stdoutFile,
                                output,
                                redirectInfo.stdoutAppend
                        );
                    } else {
                        System.out.println(output);
                    }
                } else {
                    String arg = parts.get(1);
                    String output;

                    if (builtins.contains(arg)) {
                        output = arg + " is a shell builtin";
                    } else {
                        File executable
                                = findExecutable(arg);

                        if (executable != null) {
                            output = arg + " is "
                                    + executable.getAbsolutePath();
                        } else {
                            output = arg + ": not found";
                        }
                    }

                    if (redirectInfo.stdoutFile != null) {
                        writeLineToFile(
                                redirectInfo.stdoutFile,
                                output,
                                redirectInfo.stdoutAppend
                        );
                    } else {
                        System.out.println(output);
                    }
                }
            } else if (command.equals("pwd")) {
                String output
                        = currentDir.getAbsolutePath();

                if (redirectInfo.stdoutFile != null) {
                    writeLineToFile(
                            redirectInfo.stdoutFile,
                            output,
                            redirectInfo.stdoutAppend
                    );
                } else {
                    System.out.println(output);
                }
            } else if (command.equals("cd")) {
                String arg
                        = parts.size() > 1 ? parts.get(1) : "";

                File target;

                if (arg.equals("~")) {
                    String home
                            = System.getenv("HOME");

                    target = home != null
                            ? new File(home)
                            : new File("null");
                } else {
                    target = new File(arg);

                    if (!target.isAbsolute()) {
                        target
                                = new File(currentDir, arg);
                    }
                }

                target
                        = target.toPath()
                                .normalize()
                                .toAbsolutePath()
                                .toFile();

                if (target.exists()
                        && target.isDirectory()) {
                    currentDir = target;
                } else {
                    String output
                            = "cd: " + arg
                            + ": No such file or directory";

                    if (redirectInfo.stderrFile != null) {
                        writeLineToFile(
                                redirectInfo.stderrFile,
                                output,
                                redirectInfo.stderrAppend
                        );
                    } else {
                        System.out.println(output);
                    }
                }
            } else if (command.equals("jobs")) {
                checkAndPrintJobs(System.out, true);
            } else {
                File executable
                        = findExecutable(command);

                if (executable != null) {
                    ProcessBuilder pb
                            = new ProcessBuilder(parts);

                    pb.directory(currentDir);

                    if (redirectInfo.stdoutFile != null) {
                        if (redirectInfo.stdoutAppend) {
                            pb.redirectOutput(
                                    ProcessBuilder.Redirect.appendTo(
                                            new File(
                                                    redirectInfo.stdoutFile
                                            )
                                    )
                            );
                        } else {
                            pb.redirectOutput(
                                    ProcessBuilder.Redirect.to(
                                            new File(
                                                    redirectInfo.stdoutFile
                                            )
                                    )
                            );
                        }
                    } else {
                        pb.redirectOutput(
                                ProcessBuilder.Redirect.INHERIT
                        );
                    }

                    if (redirectInfo.stderrFile != null) {
                        if (redirectInfo.stderrAppend) {
                            pb.redirectError(
                                    ProcessBuilder.Redirect.appendTo(
                                            new File(
                                                    redirectInfo.stderrFile
                                            )
                                    )
                            );
                        } else {
                            pb.redirectError(
                                    ProcessBuilder.Redirect.to(
                                            new File(
                                                    redirectInfo.stderrFile
                                            )
                                    )
                            );
                        }
                    } else {
                        pb.redirectError(
                                ProcessBuilder.Redirect.INHERIT
                        );
                    }

                    Process process = pb.start();

                    if (background) {
                        String commandStr = String.join(" ", parts) + " &";
                        int jobNo = getNextJobNumber();

                        JobInfo job = new JobInfo(
                                jobNo,
                                process,
                                commandStr
                        );
                        backgroundJobs.add(job);
                        System.out.println("[" + jobNo + "] " + process.pid());
                    } else {
                        process.waitFor();
                    }
                } else {
                    String output
                            = command + ": command not found";

                    if (redirectInfo.stderrFile != null) {
                        writeLineToFile(
                                redirectInfo.stderrFile,
                                output,
                                redirectInfo.stderrAppend
                        );
                    } else {
                        System.out.println(output);
                    }
                }
            }
        }

        sc.close();
    }
}