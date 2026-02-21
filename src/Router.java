import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Router {

    private Device me;
    private List<Device> neighbors;
    private ExecutorService es = Executors.newFixedThreadPool(4);

    private Map<String, String> forwardingTable = new HashMap<>();

    public Router(Config config) {
        this.me = config.device;
        this.neighbors = config.neighbors;
        buildForwardingTable();
    }

    private void buildForwardingTable() {
        if (me.id.equals("R1")) {
            forwardingTable.put("net1", "S1");
            forwardingTable.put("net2", "R2");
            forwardingTable.put("net3", "net2.R2");
        } else if (me.id.equals("R2")) {
            forwardingTable.put("net2", "R1");
            forwardingTable.put("net3", "S2");
            forwardingTable.put("net1", "net2.R1");
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

        byte[] buffer = new byte[4096];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
            DatagramPacket safe =
                    new DatagramPacket(copy, copy.length, packet.getAddress(), packet.getPort());

            es.submit(() -> handlePacket(safe, socket));
        }
    }

    private void handlePacket(DatagramPacket packet, DatagramSocket socket) {
        try {
            String msg = new String(packet.getData(), 0, packet.getLength());
            Frame frame = new Frame(msg);

            System.out.println("[" + me.id + "] RECEIVED  src=" + frame.src +
                    " dst=" + frame.dst +
                    " srcIP=" + frame.srcIP +
                    " dstIP=" + frame.dstIP +
                    " msg=" + frame.payload);

            // Only process frames whose MAC destination is this router
            if (!frame.dst.equals(me.id)) {
                return;
            }

            String dstSubnet = frame.dstIP.substring(0, frame.dstIP.indexOf('.'));

            // If IP is for this router
            if (frame.dstIP.endsWith("." + me.id)) {
                System.out.println("[" + me.id + "] IP packet destined to me. Received and ignored.");
                return;
            }

            // Same-subnet traffic (switch flooding case)
            if (me.id.equals("R1") && dstSubnet.equals("net1")) {
                System.out.println("[" + me.id + "] Same-subnet traffic. Received and ignored.");
                return;
            }

            if (me.id.equals("R2") && dstSubnet.equals("net3")) {
                System.out.println("[" + me.id + "] Same-subnet traffic. Received and ignored.");
                return;
            }

            String tableEntry = forwardingTable.get(dstSubnet);

            if (tableEntry == null) {
                System.out.println("[" + me.id + "] No route to subnet " + dstSubnet + ". Dropping.");
                return;
            }

            String newDstMAC;
            Device outNeighbor;

            if (tableEntry.contains(".")) {
                newDstMAC = extractId(tableEntry);
                outNeighbor = findNeighbor(newDstMAC);
            } else {
                newDstMAC = extractId(frame.dstIP);
                outNeighbor = findNeighbor(tableEntry);
            }

            if (outNeighbor == null) {
                System.out.println("[" + me.id + "] Cannot find neighbor for next hop. Dropping.");
                return;
            }

            Frame outFrame = new Frame(
                    me.id + ":" +
                            newDstMAC + ":" +
                            frame.srcIP + ":" +
                            frame.dstIP + ":" +
                            frame.payload
            );

            System.out.println("[" + me.id + "] FORWARDING src=" + outFrame.src +
                    " dst=" + outFrame.dst +
                    " srcIP=" + outFrame.srcIP +
                    " dstIP=" + outFrame.dstIP +
                    " msg=" + outFrame.payload);

            byte[] data = outFrame.toString().getBytes();
            DatagramPacket outPacket =
                    new DatagramPacket(data, data.length,
                            InetAddress.getByName(outNeighbor.ip),
                            outNeighbor.port);

            socket.send(outPacket);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String extractId(String virtualIP) {
        int dot = virtualIP.lastIndexOf('.');
        return (dot >= 0) ? virtualIP.substring(dot + 1) : virtualIP;
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
        new Router(config).start();
    }
}