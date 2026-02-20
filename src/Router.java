import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Router {

    private Device me;
    private List<Device> neighbors;
    private Map<String, Device> routingTable = new HashMap<>();
    private ExecutorService es = Executors.newFixedThreadPool(4);

    public Router(Config config) {
        this.me = config.device;
        this.neighbors = config.neighbors;

        for (Device neighbor : neighbors) {
            routingTable.put(neighbor.id, neighbor);
        }
    }

    public void start() throws Exception {
        DatagramSocket socket = new DatagramSocket(me.port);
        System.out.println("Router " + me.id + " listening on port " + me.port);

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

            System.out.println("Received frame: " + frame);

            if (frame.dst.equals(me.id)) {
                System.out.println("Packet reached router " + me.id);
                return;
            }

            Device nextHop = routingTable.get(frame.dst);

            if (nextHop == null) {
                System.out.println("No route to " + frame.dst + ". Dropping packet.");
                return;
            }

            byte[] data = frame.toString().getBytes();
            DatagramPacket outPacket = new DatagramPacket(
                    data,
                    data.length,
                    InetAddress.getByName(nextHop.ip),
                    nextHop.port
            );

            socket.send(outPacket);
            System.out.println("Forwarded packet to " + nextHop.id);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) return;
        Config config = ConfigParser.parse("config.txt", args[0]);
        new Router(config).start();
    }
}