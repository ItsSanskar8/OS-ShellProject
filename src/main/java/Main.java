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

            String input = scanner.nextLine().trim();

            if (input.equals("exit")) {
                break;
            }

            // ---------------- pwd ----------------
            if (input.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
                continue;
            }

            // ---------------- cd (ABSOLUTE PATH ONLY) ----------------
            if (input.startsWith("cd ")) {

                String path = input.substring(3).trim();

                File dir = new File(path);

                if (dir.isDirectory()) {
                    System.setProperty("user.dir", dir.getAbsolutePath());
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }

                continue;
            }

            // echo builtin
            if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
                continue;
            }

            // type builtin
            if (input.startsWith("type ")) {

                String cmd = input.substring(5).trim();

                if (isBuiltin(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                    continue;
                }

                String path = findExecutable(cmd);

                if (path != null) {
                    System.out.println(cmd + " is " + path);
                } else {
                    System.out.println(cmd + ": not found");
                }

                continue;
            }

            executeExternal(input);
        }

        scanner.close();
    }

    // ---------------- BUILTINS ----------------
    static boolean isBuiltin(String cmd) {
        return cmd.equals("echo") ||
               cmd.equals("exit") ||
               cmd.equals("type") ||
               cmd.equals("pwd") ||
               cmd.equals("cd");
    }

    // ---------------- PATH SEARCH ----------------
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

    // ---------------- EXECUTION ----------------
    static void executeExternal(String input) {

        if (input.isEmpty()) return;

        String[] parts = input.split(" ");
        String cmd = parts[0];

        String path = findExecutable(cmd);

        if (path == null) {
            System.out.println(input + ": command not found");
            return;
        }

        try {
            List<String> command = new ArrayList<>();

            command.add(cmd);

            for (int i = 1; i < parts.length; i++) {
                command.add(parts[i]);
            }

            ProcessBuilder pb = new ProcessBuilder(command);

            pb.environment().put("PATH", System.getenv("PATH"));
            pb.inheritIO();

            Process process = pb.start();
            process.waitFor();

        } catch (Exception e) {
            System.out.println("Error executing command");
        }
    }
}