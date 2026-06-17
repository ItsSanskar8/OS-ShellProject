import java.util.Scanner;

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

            // echo builtin
            if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
                continue;
            }

            // type builtin
            if (input.startsWith("type ")) {

                String cmd = input.substring(5).trim();

                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    System.out.println(cmd + ": not found");
                }

                continue;
            }

            if (!input.isEmpty()) {
                System.out.println(input + ": command not found");
            }
        }

        scanner.close();
    }
}