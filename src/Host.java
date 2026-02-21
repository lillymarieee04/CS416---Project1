import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Scanner;

public class Host {
    private Device me;
    private Device connectedSwitch;
    private DatagramSocket socket;
    private String myVirtualIP;
    private String gatewayMAC;

    public Host(Config config) {
        this.me = config.device;
        if (config.neighbors.isEmpty()) {
            throw new RuntimeException("Host must be connected to a switch");
        }
        this.connectedSwitch = config.neighbors.get(0);

        if (me.virtualIP == null) {
            throw new RuntimeException("Host " + me.id + " has no virtualIP in config");
        }
        this.myVirtualIP = me.virtualIP;

        if (me.gateway == null) {
            throw new RuntimeException("Host " + me.id + " has no gateway in config");
        }
        this.gatewayMAC = extractId(me.gateway); // "net1.R1" -> "R1"
    }

    private static String extractId(String virtualIP) {
        int dot = virtualIP.lastIndexOf('.');
        return (dot >= 0) ? virtualIP.substring(dot + 1) : virtualIP;
    }

    public void start() throws Exception {
        socket = new DatagramSocket(me.port);
        System.out.println("Host " + me.id + " started on port " + me.port);
        System.out.println("Virtual IP : " + myVirtualIP);
        System.out.println("Gateway    : " + me.gateway + " (MAC=" + gatewayMAC + ")");
        System.out.println("Switch     : " + connectedSwitch);
        System.out.println();

        Thread receiverThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[1024];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String msg = new String(packet.getData(), 0, packet.getLength());
                    Frame frame = new Frame(msg);

                    if (frame.dst.equals(me.id)) {
                        System.out.println("\n[RECEIVED] From " + frame.srcIP + ": " + frame.payload);
                    } else {
                        System.out.println("\n[DEBUG] Flooded frame not for me (dst=" + frame.dst + ")");
                    }
                    System.out.print(me.id + "> ");
                }
            } catch (Exception e) {
                System.err.println("Receiver error: " + e.getMessage());
            }
        });
        receiverThread.setDaemon(true);
        receiverThread.start();

        sendFrames();
    }

    private void sendFrames() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter messages as: <destVirtualIP> <message>");
        System.out.println("Example: net3.D hello");
        System.out.println();

        while (true) {
            try {
                System.out.print(me.id + "> ");
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;

                String[] parts = input.split("\\s+", 2);
                if (parts.length < 2) {
                    System.out.println("Invalid format. Use: <destVirtualIP> <message>");
                    continue;
                }

                String destVirtualIP = parts[0]; // e.g. "net3.D" or "net1.B"
                String message       = parts[1];

                // CRITICAL FIX: Determine destination MAC based on same/different subnet
                String dstMAC = determineDestinationMAC(destVirtualIP);

                String frameString = me.id + ":" + dstMAC + ":" + myVirtualIP + ":" + destVirtualIP + ":" + message;

                byte[] data = frameString.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(connectedSwitch.ip),
                        connectedSwitch.port
                );
                socket.send(packet);
                System.out.println("[SENT] " + frameString);

            } catch (Exception e) {
                System.err.println("Error sending frame: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private String determineDestinationMAC(String destVirtualIP) {
        // Extract subnet prefix from both IPs
        String mySubnet = extractSubnet(myVirtualIP);      // "net1" from "net1.A"
        String destSubnet = extractSubnet(destVirtualIP);  // "net3" from "net3.D"

        if (mySubnet.equals(destSubnet)) {
            // Same subnet - send directly to destination host
            return extractId(destVirtualIP);  // "B" from "net1.B"
        } else {
            // Different subnet - send to gateway router
            return gatewayMAC;  // "R1"
        }
    }
    
    private String extractSubnet(String virtualIP) {
        int dot = virtualIP.indexOf('.');
        return (dot >= 0) ? virtualIP.substring(0, dot) : virtualIP;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java Host <device-id>");
            return;
        }
        Config config = ConfigParser.parse("config.txt", args[0]);
        new Host(config).start();
    }
}