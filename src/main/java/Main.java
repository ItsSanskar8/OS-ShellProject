import java.util.Scanner;
import java.io.File;

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

            // echo
            if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
                continue;
            }

            // type builtin
            if (input.startsWith("type ")) {

                String cmd = input.substring(5).trim();

                // 1. builtin check
                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type")) {
                    System.out.println(cmd + " is a shell builtin");
                    continue;
                }

                // 2. PATH search
                String pathEnv = System.getenv("PATH");

                if (pathEnv != null) {
                    String[] paths = pathEnv.split(File.pathSeparator);

                    for (String dir : paths) {

                        File file = new File(dir, cmd);

                        if (file.exists() && file.canExecute()) {
                            System.out.println(cmd + " is " + file.getAbsolutePath());
                            cmd = null;
                            break;
                        }
                    }

                    if (cmd == null) {
                        continue;
                    }
                }

                // 3. not found
                System.out.println(cmd + ": not found");
                continue;
            }

            if (!input.isEmpty()) {
                System.out.println(input + ": command not found");
            }
        }

        scanner.close();
    }
}