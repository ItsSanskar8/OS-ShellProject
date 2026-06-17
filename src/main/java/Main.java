public class Main {
    public static void main(String[] args) {

        while (true) {

            // Print shell prompt
            System.out.print("$ ");
            System.out.flush();

            // Temporary infinite loop
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}