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
        System.out.println("Neighbors: " + neighbors);
        System.out.println();

        byte[] buffer = new byte[4096];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
            DatagramPacket safe = new DatagramPacket(copy, copy.length,
                    packet.getAddress(), packet.getPort());

            es.submit(() -> handlePacket(safe, socket));
        }
    }

    private void handlePacket(DatagramPacket packet, DatagramSocket socket) {
        String msg = new String(packet.getData(), 0, packet.getLength());

        if (msg.startsWith("DV:")) {
            return;
        }
        try {
            msg = new String(packet.getData(), 0, packet.getLength());
            Frame frame = new Frame(msg);

            Device incomingPort = findNeighbor(frame.src);

            if (incomingPort == null) {
                System.out.println("[" + me.id + "] Unknown source MAC: "
                        + frame.src + ". Dropping.\n");
                return;
            }

            if (!switchTable.containsKey(frame.src)) {
                switchTable.put(frame.src, incomingPort);
                System.out.println("[" + me.id + "] Learned: "
                        + frame.src + " is on port "
                        + incomingPort.id);
            }

            Device outgoingPort = switchTable.get(frame.dst);

            if (outgoingPort != null) {

                sendFrame(socket, frame, outgoingPort);

            } else {

                for (Device neighbor : neighbors) {
                    if (!neighbor.id.equals(incomingPort.id)) {
                        sendFrame(socket, frame, neighbor);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendFrame(DatagramSocket socket, Frame frame, Device target) throws Exception {
        byte[] data = frame.toString().getBytes();
        DatagramPacket outPacket = new DatagramPacket(
                data, data.length,
                InetAddress.getByName(target.ip),
                target.port
        );
        socket.send(outPacket);
    }

    private Device findNeighbor(String id) {
        for (Device d : neighbors) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) return;
        Config config = ConfigParser.parse("config.txt", args[0]);
        new Switch(config).start();
    }
}