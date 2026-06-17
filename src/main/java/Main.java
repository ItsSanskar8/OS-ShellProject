import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {

        System.out.print("$ ");
        System.out.flush();

        Scanner sc = new Scanner(System.in);

        String input = sc.nextLine();

        System.out.println(input + ": command not found");
    }
}