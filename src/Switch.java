import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Switch {
    private Device me;
    private List<Device> neighbors;
    private Map<String, Device> switchTable = new HashMap<>();
    private ExecutorService es = Executors.newFixedThreadPool(4);

    public Switch(Config config) {
        this.me = config.device;
        this.neighbors = config.neighbors;
    }

    public void start() throws Exception {
        DatagramSocket socket = new DatagramSocket(me.port);
        System.out.println("Switch " + me.id + " listening on port " + me.port);

        byte[] buffer = new byte[1024];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            es.submit(() -> handlePacket(packet, socket));
        }
    }

    private void handlePacket(DatagramPacket packet, DatagramSocket socket) {
        try {
            String msg = new String(packet.getData(), 0, packet.getLength());
            Frame frame = new Frame(msg);

            Device incomingPort = null;
            String srcIp = packet.getAddress().getHostAddress();
            int srcPort = packet.getPort();

            for (Device neighbor : neighbors) {
                if (neighbor.ip.equals(srcIp) && neighbor.port == srcPort) {
                    incomingPort = neighbor;
                    break;
                }
            }

            if (incomingPort == null) {
                System.out.println("Port not recognized. Dropping packet.");
                return;
            }

            if (!switchTable.containsKey(frame.src)) {
                switchTable.put(frame.src, incomingPort);
                System.out.println(frame.src + " is on port " + incomingPort.id);
            }

            System.out.println("Switch Table:");
            for (Map.Entry<String, Device> entry : switchTable.entrySet()) {
                System.out.println("MAC " + entry.getKey() + " to Port " + entry.getValue().id);
            }
            System.out.println();

            Device outgoingPort = switchTable.get(frame.dst);

            if (outgoingPort != null) {
                byte[] data = frame.toString().getBytes();
                DatagramPacket outPacket = new DatagramPacket(data, data.length, InetAddress.getByName(outgoingPort.ip), outgoingPort.port);
                socket.send(outPacket);
                System.out.println("Sent frame to " + outgoingPort.id);
            } else {
                System.out.println("Flooding frame to " + frame.dst);
                for (Device neighbor : neighbors) {
                    if (!neighbor.equals(incomingPort)) {
                        byte[] data = frame.toString().getBytes();
                        DatagramPacket outPacket = new DatagramPacket(
                                data,
                                data.length,
                                InetAddress.getByName(neighbor.ip),
                                neighbor.port
                        );
                        socket.send(outPacket);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java Switch <device-id>");
            return;
        }
    }
}
