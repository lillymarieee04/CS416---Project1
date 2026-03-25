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

        byte[] buffer = new byte[4096];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
            es.submit(() -> handlePacket(new DatagramPacket(copy, copy.length, packet.getAddress(), packet.getPort()), socket));
        }
    }

    private void handlePacket(DatagramPacket packet, DatagramSocket socket) {
        try {
            String msg = new String(packet.getData(), 0, packet.getLength());
            Frame frame = new Frame(msg); // This now uses the 6-field constructor

            // Learning logic
            Device incomingPort = findNeighbor(frame.src);
            if (incomingPort != null && !switchTable.containsKey(frame.src)) {
                switchTable.put(frame.src, incomingPort);
                System.out.println("[" + me.id + "] Learned: " + frame.src + " on " + incomingPort.id);
            }

            // Forwarding logic
            if (frame.dst.equals("BROADCAST")) {
                // Flood broadcast packets (used for Routing Updates)
                flood(socket, frame, incomingPort);
            } else {
                Device outgoingPort = switchTable.get(frame.dst);
                if (outgoingPort != null) {
                    sendFrame(socket, frame, outgoingPort);
                } else {
                    flood(socket, frame, incomingPort);
                }
            }
        } catch (Exception e) {
            // Silence errors from malformed packets
        }
    }

    private void flood(DatagramSocket socket, Frame frame, Device incomingPort) throws Exception {
        for (Device neighbor : neighbors) {
            if (incomingPort == null || !neighbor.id.equals(incomingPort.id)) {
                sendFrame(socket, frame, neighbor);
            }
        }
    }

    private void sendFrame(DatagramSocket socket, Frame frame, Device target) throws Exception {
        byte[] data = frame.toString().getBytes();
        socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(target.ip), target.port));
    }

    private Device findNeighbor(String id) {
        for (Device d : neighbors) if (d.id.equals(id)) return d;
        return null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) return;
        Config config = ConfigParser.parse("config.txt", args[0]);
        new Switch(config).start();
    }
}