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

            List<String> tokens = tokenize(input);

            List<String> cmd = new ArrayList<>();
            String outputFile = null;

            for (int i = 0; i < tokens.size(); i++) {

                String t = tokens.get(i);

                if (t.equals(">") || t.equals("1>")) {
                    if (i + 1 < tokens.size()) {
                        outputFile = tokens.get(i + 1);
                        i++;
                    }
                } else {
                    cmd.add(t);
                }
            }

            if (cmd.isEmpty()) continue;

            String command = cmd.get(0);

            if (command.equals("echo")) {
                String out = String.join(" ", cmd.subList(1, cmd.size()));
                writeOutput(out, outputFile);
                continue;
            }

            if (command.equals("type")) {

                String arg = cmd.size() > 1 ? cmd.get(1) : "";

                if (isBuiltin(arg)) {
                    writeOutput(arg + " is a shell builtin", outputFile);
                } else {
                    String found = findExecutable(arg);
                    if (found != null) {
                        writeOutput(arg + " is " + found, outputFile);
                    } else {
                        System.out.println(arg + ": not found");
                    }
                }

                continue;
            }

            runExternal(cmd, outputFile);
        }

        scanner.close();
    }

    static boolean isBuiltin(String cmd) {
        return cmd.equals("echo")
                || cmd.equals("exit")
                || cmd.equals("type")
                || cmd.equals("pwd")
                || cmd.equals("cd");
    }

    static void writeOutput(String text, String file) {

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
            File f = new File(dir, cmd);
            if (f.exists() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }

        return null;
    }

    static void runExternal(List<String> cmd, String outputFile) {

        String exe = cmd.get(0);

        if (findExecutable(exe) == null) {
            System.out.println(String.join(" ", cmd) + ": command not found");
            return;
        }

        try {

            ProcessBuilder pb = new ProcessBuilder(cmd);

            if (outputFile != null) {
                pb.redirectOutput(new File(outputFile));
            }

            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process p = pb.start();
            p.waitFor();

        } catch (Exception e) {
            System.out.println("Error executing command");
        }
    }

    static List<String> tokenize(String input) {

        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;
        boolean escape = false;

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    cur.append(c);
                }
                continue;
            }

            if (inDouble) {

                if (escape) {
                    if (c == '"' || c == '\\') {
                        cur.append(c);
                    } else {
                        cur.append('\\').append(c);
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

                cur.append(c);
                continue;
            }

            if (escape) {
                cur.append(c);
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
                if (cur.length() > 0) {
                    result.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }

        if (cur.length() > 0) {
            result.add(cur.toString());
        }

        return result;
    }
}