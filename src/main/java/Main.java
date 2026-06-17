import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        while (true) {

            // Display shell prompt
            System.out.print("$ ");
            System.out.flush();

            // Read user command
            String input = scanner.nextLine();

            // Print command not found message
            System.out.println(input + ": command not found");
        }
    }
}