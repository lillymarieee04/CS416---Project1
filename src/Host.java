//A host is always waiting for the user to enter a short message together with the
//intended receiver. The host then generates a virtual frame.

//The host then creates a UDP packet to carry the virtual frame and sends it to the
//connected switch using the switch’s IP address and port number.


//2 threads (while true loops)

public class Host {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java Program <ID>");
            return;
        }

        Config config = ConfigParser.parse("config.txt", args[0]);

        System.out.println("I am " + config.device);
        System.out.println("My neighbors:");
        for (Device d : config.neighbors) {
            System.out.println(" - " + d);
        }
    }
}
