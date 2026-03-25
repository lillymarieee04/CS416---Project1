import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;

public class Router {
    private Device me;
    private List<Device> neighbors;
    private ExecutorService es = Executors.newFixedThreadPool(4);

    // Subnet -> Distance (cost)
    private Map<String, Integer> distanceVector = new ConcurrentHashMap<>();
    // Subnet -> NextHop Device ID
    private Map<String, String> forwardingTable = new ConcurrentHashMap<>();

    public Router(Config config) {
        this.me = config.device;
        this.neighbors = config.neighbors;
        initializeLocalSubnets();
    }

    /**
     * Project 3 requirement: Automatically discover directly connected subnets.
     * Based on virtualIPs assigned in config.txt (e.g., net1.R1).
     */
    private void initializeLocalSubnets() {
        if (me.virtualIP != null) {
            // A router might have multiple virtual IPs separated by space in your config
            String[] vips = me.virtualIP.split("\\s+");
            for (String vip : vips) {
                String subnet = vip.substring(0, vip.indexOf('.'));
                distanceVector.put(subnet, 0); // Cost to local subnet is 0
                // For local subnets, the "next hop" is the edge switch or host directly
                // We'll infer the nextHop from neighbors during routing updates
            }
        }
    }

    public void start() throws Exception {
        DatagramSocket socket = new DatagramSocket(me.port);
        System.out.println("Router " + me.id + " started (Distance Vector enabled)");

        // Thread to periodically broadcast distance vector to neighbors
        new Thread(() -> {
            while (true) {
                try {
                    broadcastUpdates(socket);
                    Thread.sleep(5000); // Send updates every 5 seconds
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

        byte[] buffer = new byte[4096];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
            es.submit(() -> handlePacket(new DatagramPacket(copy, copy.length, packet.getAddress(), packet.getPort()), socket));
        }
    }

    private void broadcastUpdates(DatagramSocket socket) throws Exception {
        // Format: subnet1:dist1,subnet2:dist2...
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : distanceVector.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(entry.getKey()).append(":").append(entry.getValue());
        }

        // Type 1 = Routing Update
        String routingPayload = "1:" + me.id + ":BROADCAST:0.0.0.0:0.0.0.0:" + sb.toString();
        byte[] data = routingPayload.getBytes();

        for (Device neighbor : neighbors) {
            DatagramPacket p = new DatagramPacket(data, data.length, InetAddress.getByName(neighbor.ip), neighbor.port);
            socket.send(p);
        }
    }

    private void handlePacket(DatagramPacket packet, DatagramSocket socket) {
        try {
            String msg = new String(packet.getData(), 0, packet.getLength());
            String[] parts = msg.split(":", 6); // 0:type, 1:src, 2:dst, 3:srcIP, 4:dstIP, 5:payload

            int type = Integer.parseInt(parts[0]);
            String srcId = parts[1];
            String dstId = parts[2];
            String payload = parts[5];

            if (type == 1) {
                processRoutingUpdate(srcId, payload);
            } else {
                processDataPacket(msg, parts, socket);
            }
        } catch (Exception e) {
            System.err.println("Error handling packet: " + e.getMessage());
        }
    }

    private void processRoutingUpdate(String neighborId, String payload) {
        boolean changed = false;
        String[] updates = payload.split(",");

        for (String update : updates) {
            String[] kv = update.split(":");
            String subnet = kv[0];
            int neighborDist = Integer.parseInt(kv[1]);
            int newDist = neighborDist + 1; // Uniform cost of 1

            if (!distanceVector.containsKey(subnet) || newDist < distanceVector.get(subnet)) {
                distanceVector.put(subnet, newDist);
                forwardingTable.put(subnet, neighborId);
                changed = true;
            }
        }

        if (changed) {
            System.out.println("[" + me.id + "] Table Updated: " + distanceVector);
        }
    }

    private void processDataPacket(String raw, String[] parts, DatagramSocket socket) throws Exception {
        String dstId = parts[2];
        String dstIP = parts[4];

        if (!dstId.equals(me.id)) return;

        String dstSubnet = dstIP.substring(0, dstIP.indexOf('.'));
        String nextHopId = forwardingTable.get(dstSubnet);

        if (nextHopId == null) {
            System.out.println("[" + me.id + "] No route to " + dstSubnet);
            return;
        }

        Device outNeighbor = findNeighbor(nextHopId);
        if (outNeighbor == null) return;

        // Determine destination MAC: if next hop is a router, use its ID; otherwise use the host ID
        String newDstMAC = nextHopId.startsWith("R") ? nextHopId : extractId(dstIP);

        // Rebuild frame with correct MACs (Type 0 for Data)
        String forwardMsg = "0:" + me.id + ":" + newDstMAC + ":" + parts[3] + ":" + parts[4] + ":" + parts[5];
        byte[] data = forwardMsg.getBytes();

        socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(outNeighbor.ip), outNeighbor.port));
    }

    private Device findNeighbor(String id) {
        for (Device d : neighbors) if (d.id.equals(id)) return d;
        return null;
    }

    private String extractId(String virtualIP) {
        int dot = virtualIP.lastIndexOf('.');
        return (dot >= 0) ? virtualIP.substring(dot + 1) : virtualIP;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) return;
        Config config = ConfigParser.parse("config.txt", args[0]);
        new Router(config).start();
    }
}