import java.util.Scanner;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print("$ ");
            System.out.flush();

            String input = scanner.nextLine();

            if (input.equals("exit")) {
                break;
            }

            if (input.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
                continue;
            }

            if (input.startsWith("cd ")) {

                String path = input.substring(3).trim();

                File target;

                if (path.equals("~")) {
                    target = new File(System.getenv("HOME"));
                } else if (path.startsWith("/")) {
                    target = new File(path);
                } else {
                    File current = new File(System.getProperty("user.dir"));
                    target = new File(current, path);
                }

                try {
                    File resolved = new File(target.getCanonicalPath());

                    if (resolved.isDirectory()) {
                        System.setProperty("user.dir", resolved.getAbsolutePath());
                    } else {
                        System.out.println("cd: " + path + ": No such file or directory");
                    }

                } catch (Exception e) {
                    System.out.println("cd: " + path + ": No such file or directory");
                }

                continue;
            }

            List<String> parts = tokenize(input);

            if (parts.isEmpty()) continue;

            List<String> cmdParts = new ArrayList<>();
            String outputFile = null;

            for (int i = 0; i < parts.size(); i++) {
                String p = parts.get(i);

                if ((p.equals(">") || p.equals("1>")) && i + 1 < parts.size()) {
                    outputFile = parts.get(i + 1);
                    i++;
                } else {
                    cmdParts.add(p);
                }
            }

            if (cmdParts.isEmpty()) continue;

            String cmd = cmdParts.get(0);

            if (cmd.equals("echo")) {
                String out = String.join(" ", cmdParts.subList(1, cmdParts.size()));
                handleOutput(out, outputFile);
                continue;
            }

            if (cmd.equals("type")) {

                String arg = cmdParts.size() > 1 ? cmdParts.get(1) : "";

                if (arg.equals("echo") || arg.equals("exit") || arg.equals("type") || arg.equals("pwd") || arg.equals("cd")) {
                    handleOutput(arg + " is a shell builtin", outputFile);
                } else {
                    String found = findExecutable(arg);
                    if (found != null) {
                        handleOutput(arg + " is " + found, outputFile);
                    } else {
                        System.out.println(arg + ": not found");
                    }
                }

                continue;
            }

            executeExternal(cmdParts, outputFile);
        }

        scanner.close();
    }

    static void handleOutput(String text, String file) {

        if (file == null) {
            System.out.println(text);
            return;
        }

        try {
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(text.getBytes());
            fos.close();
        } catch (Exception e) {
            System.out.println(text);
        }
    }

    static String findExecutable(String cmd) {

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String[] paths = pathEnv.split(File.pathSeparator);

        for (String dir : paths) {
            File file = new File(dir, cmd);

            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    static void executeExternal(List<String> parts, String outputFile) {

        String cmd = parts.get(0);

        String path = findExecutable(cmd);

        if (path == null) {
            System.out.println(String.join(" ", parts) + ": command not found");
            return;
        }

        try {

            List<String> command = new ArrayList<>(parts);

            ProcessBuilder pb = new ProcessBuilder(command);

            if (outputFile != null) {
                pb.redirectOutput(new File(outputFile));
            }

            pb.inheritIO();

            Process process = pb.start();
            process.waitFor();

        } catch (Exception e) {
            System.out.println("Error executing command");
        }
    }

    static List<String> tokenize(String input) {

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;
        boolean escape = false;

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (inDouble) {

                if (escape) {
                    if (c == '"' || c == '\\') {
                        current.append(c);
                    } else {
                        current.append('\\').append(c);
                    }
                    escape = false;
                    continue;
                }

                if (c == '\\') {
                    escape = true;
                    continue;
                }

                if (c == '"') {
                    inDouble = false;
                    continue;
                }

                current.append(c);
                continue;
            }

            if (escape) {
                current.append(c);
                escape = false;
                continue;
            }

            if (c == '\\') {
                escape = true;
                continue;
            }

            if (c == '\'') {
                inSingle = true;
                continue;
            }

            if (c == '"') {
                inDouble = true;
                continue;
            }

            if (c == ' ') {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }
}