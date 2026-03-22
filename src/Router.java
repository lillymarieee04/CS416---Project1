import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//For Project 3 Iteration - Use this branch

public class Router {

    private Device me;
    private List<Device> neighbors;
    private ExecutorService es = Executors.newFixedThreadPool(4);
    private Map<String, String> forwardingTable = new HashMap<>();

    private Map<String, RoutingEntry> routingTable = new HashMap<>();
    private Map<String, Map<String, Integer>> neighborsDVs = new HashMap<>();
    private Map<String, Integer> neighborCosts = new HashMap<>();


    private Map<String, String> linkToSubnet = new HashMap<>();

    class RoutingEntry {
        String nextHop;
        int cost;

        RoutingEntry(String nextHop, int cost) {
            this.nextHop = nextHop;
            this.cost = cost;
        }

        @Override
        public String toString() {
            return "via " + nextHop + " (cost=" + cost + ")";
        }    
    }

    public Router(Config config) {
        this.me = config.device;
        this.neighbors = config.neighbors;
        buildForwardingTable();
    }

    private void buildForwardingTable() {

        if (me.id.equals("R1")) {
            forwardingTable.put("net1", "S1");
            forwardingTable.put("net2", "R2");
            forwardingTable.put("net3", "R2");
        }
        else if (me.id.equals("R2")) {
            forwardingTable.put("net3", "S2");
            forwardingTable.put("net2", "R1");
            forwardingTable.put("net1", "R1");
        }

        System.out.println("Router " + me.id + " forwarding table:");
        for (Map.Entry<String, String> e : forwardingTable.entrySet()) {
            System.out.println("  " + e.getKey() + " -> " + e.getValue());
        }
        System.out.println();
    }

    public void start() throws Exception {
        DatagramSocket socket = new DatagramSocket(me.port);
        System.out.println("Router " + me.id + " listening on port " + me.port);
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
        try {
            String msg = new String(packet.getData(), 0, packet.getLength());
            Frame frame = new Frame(msg);

            System.out.println("[" + me.id + "] RECEIVED src=" + frame.src +
                    " dst=" + frame.dst +
                    " srcIP=" + frame.srcIP +
                    " dstIP=" + frame.dstIP +
                    " msg=" + frame.payload);

            if (!frame.dst.equals(me.id)) {
                System.out.println("[" + me.id + "] DEBUG: Wrong frame received (dst="
                        + frame.dst + "). Ignored.");
                return;
            }

            String dstSubnet = frame.dstIP.substring(0, frame.dstIP.indexOf('.'));

            String nextHopId = forwardingTable.get(dstSubnet);

            if (nextHopId == null) {
                System.out.println("[" + me.id + "] No route to subnet "
                        + dstSubnet + ". Dropping.\n");
                return;
            }

            Device outNeighbor = findNeighbor(nextHopId);

            if (outNeighbor == null) {
                System.out.println("[" + me.id + "] Cannot find neighbor "
                        + nextHopId + ". Dropping.\n");
                return;
            }

            String newDstMAC;

            if (nextHopId.startsWith("R")) {
                newDstMAC = nextHopId;
            }
            else {
                newDstMAC = extractId(frame.dstIP);
            }

            Frame outFrame = new Frame(
                    me.id + ":" + newDstMAC + ":" +
                            frame.srcIP + ":" + frame.dstIP + ":" + frame.payload
            );

            System.out.println("[" + me.id + "] FORWARDING to "
                    + nextHopId + " (dstMAC=" + newDstMAC + ")\n");

            byte[] data = outFrame.toString().getBytes();
            DatagramPacket outPacket = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(outNeighbor.ip),
                    outNeighbor.port
            );

            socket.send(outPacket);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Device findNeighbor(String id) {
        for (Device d : neighbors) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    private static String extractId(String virtualIP) {
        int dot = virtualIP.lastIndexOf('.');
        return (dot >= 0) ? virtualIP.substring(dot + 1) : virtualIP;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) return;
        Config config = ConfigParser.parse("config.txt", args[0]);
        new Router(config).start();
    }
}