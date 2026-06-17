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

            if (input.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
                continue;
            }

            if (input.startsWith("cd ")) {

                String path = input.substring(3).trim();

                File target;

                if (path.equals("~")) {
                    String home = System.getenv("HOME");
                    target = new File(home);
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

            if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
                continue;
            }

            if (input.startsWith("type ")) {

                String cmd = input.substring(5).trim();

                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd") || cmd.equals("cd")) {
                    System.out.println(cmd + " is a shell builtin");
                    continue;
                }

                String pathEnv = System.getenv("PATH");
                String found = null;

                if (pathEnv != null) {
                    String[] paths = pathEnv.split(File.pathSeparator);

                    for (String dir : paths) {
                        File file = new File(dir, cmd);

                        if (file.exists() && file.canExecute()) {
                            found = file.getAbsolutePath();
                            break;
                        }
                    }
                }

                if (found != null) {
                    System.out.println(cmd + " is " + found);
                } else {
                    System.out.println(cmd + ": not found");
                }

                continue;
            }

            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split(" ");
            String cmd = parts[0];

            String pathEnv = System.getenv("PATH");
            String found = null;

            if (pathEnv != null) {
                String[] paths = pathEnv.split(File.pathSeparator);

                for (String dir : paths) {
                    File file = new File(dir, cmd);

                    if (file.exists() && file.canExecute()) {
                        found = file.getAbsolutePath();
                        break;
                    }
                }
            }

            if (found == null) {
                System.out.println(input + ": command not found");
                continue;
            }

            try {

                List<String> command = new ArrayList<>();
                command.add(cmd);

                for (int i = 1; i < parts.length; i++) {
                    command.add(parts[i]);
                }

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.inheritIO();

                Process process = pb.start();
                process.waitFor();

            } catch (Exception e) {
                System.out.println("Error executing command");
            }
        }

        scanner.close();
    }
}