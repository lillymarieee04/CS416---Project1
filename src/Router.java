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

    private Map<String, RoutingEntry> routingTable = Collections.synchronizedMap(new HashMap<>());
    private Map<String, Map<String, Integer>> neighborsDVs = Collections.synchronizedMap(new HashMap<>());

    private Map<String, String> subnetMap;

    class RoutingEntry {
        String nextHop;
        int cost;

        RoutingEntry(String nextHop, int cost) {
            this.nextHop = nextHop;
            this.cost = cost;
        }
    }

    public Router(Config config) {
        this.me = config.device;
        this.neighbors = config.neighbors;
        this.subnetMap = config.subnetMap;
    }

    private void initializeDirectRoutes() {
        if (me.virtualIPs != null) {
            for (String vip : me.virtualIPs) {
                String subnet = vip.substring(0, vip.lastIndexOf('.'));
                routingTable.put(subnet, new RoutingEntry(me.id, 0));
                System.out.println("[" + me.id + "] Direct: " + subnet);
            }
        }

        for (Device neighbor : neighbors) {
            String subnet = subnetMap.get(me.id + "-" + neighbor.id);

            if (subnet != null) {
                routingTable.put(subnet, new RoutingEntry(neighbor.id, 1));
                System.out.println("[" + me.id + "] Neighbor route: " + subnet + " via " + neighbor.id);
            }
        }
    }

    public void start() throws Exception {
        DatagramSocket socket = new DatagramSocket(me.port);

        System.out.println("Router " + me.id + " started");

        initializeDirectRoutes();
        broadcastDistanceVector();

        byte[] buffer = new byte[4096];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
            DatagramPacket safe = new DatagramPacket(copy, copy.length, packet.getAddress(), packet.getPort());

            es.submit(() -> handlePacket(safe, socket));
        }
    }

    private void BellmanFordAlgo() {
        boolean updated = false;

        for (String neighborId : neighborsDVs.keySet()) {
            Map<String, Integer> neighborDV = neighborsDVs.get(neighborId);

            for (Map.Entry<String, Integer> entry : neighborDV.entrySet()) {
                String subnet = entry.getKey();
                int cost = 1 + entry.getValue();

                RoutingEntry current = routingTable.get(subnet);

                if (current == null || cost < current.cost) {
                    routingTable.put(subnet, new RoutingEntry(neighborId, cost));
                    updated = true;

                    System.out.println("[" + me.id + "] Updated: " + subnet + " via " + neighborId);
                }
            }
        }

        if (updated) broadcastDistanceVector();
    }

    private void handleDVUpdate(String msg) {
        try {
            String[] parts = msg.split(":", 3);
            String sender = parts[1];
            String data = parts[2];

            Map<String, Integer> dv = new HashMap<>();

            if (!data.isEmpty()) {
                for (String e : data.split(",")) {
                    String[] kv = e.split("=");
                    if (kv.length == 2) {
                        dv.put(kv[0], Integer.parseInt(kv[1]));
                    }
                }
            }

            neighborsDVs.put(sender, dv);
            BellmanFordAlgo();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handlePacket(DatagramPacket packet, DatagramSocket socket) {
        try {
            String msg = new String(packet.getData(), 0, packet.getLength());
            if (msg.startsWith("DV:")) {
                handleDVUpdate(msg);
                return;
            }

            Frame frame = new Frame(msg);

            handleUserPacket(frame, socket);

        } catch (Exception e) {
            System.out.println("[" + me.id + "] ERROR processing packet");
            e.printStackTrace();
        }
    }

    private void handleUserPacket(Frame frame, DatagramSocket socket) {
        try {
            String dstSubnet = frame.dstIP.substring(0, frame.dstIP.lastIndexOf('.'));
            RoutingEntry route = routingTable.get(dstSubnet);

            if (route == null) {
                System.out.println("[" + me.id + "] No route to " + dstSubnet);
                return;
            }
            System.out.println("[" + me.id + "] Forwarding Frame: " + frame.toString());

            Device nextHop = findNeighbor(route.nextHop);

            if (nextHop == null) {
                System.out.println("[" + me.id + "] Next hop not found: " + route.nextHop);
                return;
            }

            frame.src = me.id;

            byte[] data = frame.toString().getBytes();

            DatagramPacket outPacket = new DatagramPacket(
                    data,
                    data.length,
                    InetAddress.getByName(nextHop.ip),
                    nextHop.port
            );

            socket.send(outPacket);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void broadcastDistanceVector() {
        Map<String, Integer> dv = new HashMap<>();

        for (Map.Entry<String, RoutingEntry> e : routingTable.entrySet()) {
            dv.put(e.getKey(), e.getValue().cost);
        }

        String msg = serializeDV(me.id, dv);
        byte[] data = msg.getBytes();

        for (Device neighbor : neighbors) {
            try (DatagramSocket s = new DatagramSocket()) {
                DatagramPacket p = new DatagramPacket(
                        data,
                        data.length,
                        InetAddress.getByName(neighbor.ip),
                        neighbor.port
                );
                s.send(p);
            } catch (Exception e) {
                System.err.println("Failed to send DV to " + neighbor.id);
            }
        }
    }

    private String serializeDV(String id, Map<String, Integer> dv) {
        StringBuilder sb = new StringBuilder("DV:");
        sb.append(id).append(":");

        for (Map.Entry<String, Integer> e : dv.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(",");
        }

        return sb.toString();
    }

    private Device findNeighbor(String id) {
        for (Device d : neighbors) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Config config = ConfigParser.parse("config.txt", args[0]);
        new Router(config).start();
    }
}
