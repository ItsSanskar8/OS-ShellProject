import java.util.Scanner;

public class Main {

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print("$ ");
            System.out.flush();

            String input = scanner.nextLine().trim();

            // exit builtin
            if (input.equals("exit")) {
                break;
            }

            // echo builtin
            if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
                continue;
            }

            // empty input
            if (input.isEmpty()) {
                continue;
            }

            // fallback
            System.out.println(input + ": command not found");
        }

        scanner.close();
    }
}