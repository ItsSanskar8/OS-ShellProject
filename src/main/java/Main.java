import java.util.Scanner;
import java.io.File;
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

            String cmd = parts.get(0);

            if (cmd.equals("echo")) {
                System.out.println(String.join(" ", parts.subList(1, parts.size())));
                continue;
            }

            if (cmd.equals("type")) {

                String arg = parts.size() > 1 ? parts.get(1) : "";

                if (arg.equals("echo") || arg.equals("exit") || arg.equals("type") || arg.equals("pwd") || arg.equals("cd")) {
                    System.out.println(arg + " is a shell builtin");
                } else {
                    String found = findExecutable(arg);
                    if (found != null) {
                        System.out.println(arg + " is " + found);
                    } else {
                        System.out.println(arg + ": not found");
                    }
                }

                continue;
            }

            executeExternal(parts);
        }

        scanner.close();
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

    static void executeExternal(List<String> parts) {

        String cmd = parts.get(0);

        String path = findExecutable(cmd);

        if (path == null) {
            System.out.println(String.join(" ", parts) + ": command not found");
            return;
        }

        try {

            List<String> command = new ArrayList<>();
            command.add(cmd);

            for (int i = 1; i < parts.size(); i++) {
                command.add(parts.get(i));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
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
                current.append(c);
                continue;
            }

            if (escape) {
                current.append(c);
                escape = false;
                continue;
            }

            if (!inDouble && c == '\\') {
                escape = true;
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (c == ' ' && !inSingle && !inDouble) {

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