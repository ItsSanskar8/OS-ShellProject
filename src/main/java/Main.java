import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        while (true) {

            // Display shell prompt
            System.out.print("$ ");
            System.out.flush();

            // Read user command
            String input = scanner.nextLine().trim();

            // Exit builtin
            if (input.equals("exit")) {
                break;
            }

            // Command not found
            if (!input.isEmpty()) {
                System.out.println(input + ": command not found");
            }
        }

        scanner.close();
    }
}