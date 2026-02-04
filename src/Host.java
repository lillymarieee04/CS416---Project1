//A host is always waiting for the user to enter a short message together with the
//intended receiver. The host then generates a virtual frame.

//The host then creates a UDP packet to carry the virtual frame and sends it to the
//connected switch using the switch’s IP address and port number.


//2 threads (while true loops)


import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Scanner;


public class Host {
    private Device me;
    private Device connectedSwitch;
    private DatagramSocket socket;

    public Host(Config config) {
        this.me = config.device;
        if (config.neighbors.isEmpty()) {
            throw new RuntimeException("Host must be connected to a switch");
        }
        this.connectedSwitch = config.neighbors.get(0);
    }

    public void start() throws Exception {
        socket = new DatagramSocket(me.port);
        System.out.println("Host " + me.id + " started on port " + me.port);
        System.out.println("Connected to switch: " + connectedSwitch);
        System.out.println();

        Thread receiverThread = new Thread(() -> {

        });
        receiverThread.setDaemon(true);
        receiverThread.start();

        sendFrames();
    }

    private void sendFrames() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter messages in the format: <destination> <message>");
        System.out.println("Example: D hello");
        System.out.println();

        while (true) {
            try {
                System.out.print(me.id + "> ");
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                String[] parts = input.split("\\s+", 2);
                if (parts.length < 2) {
                    System.out.println("Invalid format. Use: <destination> <message>");
                    continue;
                }

                String destination = parts[0];
                String message = parts[1];

                String frameString = me.id + ":" + destination + ":" + message;

                byte[] data = frameString.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        InetAddress.getByName(connectedSwitch.ip),
                        connectedSwitch.port
                );
                socket.send(packet);

                System.out.println("Sent frame: " + frameString);

            } catch (Exception e) {
                System.err.println("Error sending frame: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}