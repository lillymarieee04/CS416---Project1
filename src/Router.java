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

    // Dynamic Routing Structures
    private Map<String, RoutingEntry> routingTable = Collections.synchronizedMap(new HashMap<>());
    private Map<String, Map<String, Integer>> neighborsDVs = Collections.synchronizedMap(new HashMap<>());
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
        //  initializeSubnetMappings();
    }



    //Need to find a better way to do this, maybe add subnet info to the config file? For now, hardcoding based on topology diagram


    // private void initializeSubnetMappings() {
    //     // Mapping physical links to subnets as defined in the topology
    //     // Host Subnets
    //     linkToSubnet.put("R1-S1", "net1"); linkToSubnet.put("S1-R1", "net1");
    //     linkToSubnet.put("R3-S2", "net2"); linkToSubnet.put("S2-R3", "net2");
    //     linkToSubnet.put("R6-S3", "net3"); linkToSubnet.put("S3-R6", "net3");

    //     // Transit Subnets
    //     linkToSubnet.put("R1-R2", "net4"); linkToSubnet.put("R2-R1", "net4");
    //     linkToSubnet.put("R1-R3", "net5"); linkToSubnet.put("R3-R1", "net5");
    //     linkToSubnet.put("R2-R3", "net6"); linkToSubnet.put("R3-R2", "net6");
    //     linkToSubnet.put("R2-R4", "net7"); linkToSubnet.put("R4-R2", "net7");
    //     linkToSubnet.put("R3-R5", "net8"); linkToSubnet.put("R5-R3", "net8");
    //     linkToSubnet.put("R4-R5", "net9"); linkToSubnet.put("R5-R4", "net9");
    //     linkToSubnet.put("R4-R6", "net10"); linkToSubnet.put("R6-R4", "net10");
    // }

    private void initializeDirectRoutes() {
        // Add this router's own virtual subnet at cost 0
        if (me.virtualIP != null) {
            String mySubnet = me.virtualIP.substring(0, me.virtualIP.lastIndexOf('.'));
            routingTable.put(mySubnet, new RoutingEntry(me.id, 0));
            System.out.println("[" + me.id + "] Direct route: " + mySubnet + " (cost=0)");
        }
        // Add directly connected neighbor subnets at cost 1
        for (Device neighbor : neighbors) {
            if (neighbor.virtualIP != null) {
                String subnet = neighbor.virtualIP.substring(0, neighbor.virtualIP.lastIndexOf('.'));
                if (!routingTable.containsKey(subnet)) {
                    routingTable.put(subnet, new RoutingEntry(neighbor.id, 1));
                    System.out.println("[" + me.id + "] Direct route: " + subnet + " via " + neighbor.id + " (cost=1)");
                }
            }
        }
    }

    public void start() throws Exception {
        DatagramSocket socket = new DatagramSocket(me.port);
        System.out.println("Router " + me.id + " started (Distance Vector Routing)");
        System.out.println("Listening on Port: " + me.port + "\n");

        initializeDirectRoutes();
        broadcastDistanceVector();


        byte[] buffer = new byte[4096];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
            DatagramPacket safe = new DatagramPacket(
                    copy, copy.length, packet.getAddress(), packet.getPort());

            es.submit(() -> handlePacket(safe, socket));
        }
    }


    //Algorithm
    private void runBellmanFord() {
        boolean updated = false;
        int linkCost = 1; // Project Requirement: Uniform cost of 1

        for (String neighborId : neighborsDVs.keySet()) {
            Map<String, Integer> neighborDV = neighborsDVs.get(neighborId);

            for (Map.Entry<String, Integer> entry : neighborDV.entrySet()) {
                String subnet = entry.getKey();
                int totalCost = linkCost + entry.getValue();

                RoutingEntry currentEntry = routingTable.get(subnet);

                // Bellman-Ford Update Rule: If subnet is new OR path is shorter
                // Never overwrite a direct route (cost 0) with a learned one
                if (currentEntry == null || (totalCost < currentEntry.cost && currentEntry.cost != 0)) {
                    routingTable.put(subnet, new RoutingEntry(neighborId, totalCost));
                    updated = true;
                    System.out.println("[" + me.id + "] Updated route: " + subnet +
                            " via " + neighborId + " (cost=" + totalCost + ")");
                }
            }
        }

        if (updated) {

            broadcastDistanceVector();
        }
    }

    private void handleDVUpdate(String dvMessage) {
        try {
            String[] parts = dvMessage.split(":", 3);
            String senderID = parts[1];
            String dvData = parts[2];

            Map<String, Integer> neighborDV = new HashMap<>();
            if (!dvData.isEmpty()) {
                String[] entries = dvData.split(",");
                for (String entry : entries) {
                    String[] kv = entry.split("=");
                    if (kv.length == 2) {
                        neighborDV.put(kv[0], Integer.parseInt(kv[1]));
                    }
                }
            }

            neighborsDVs.put(senderID, neighborDV);
            runBellmanFord();

        } catch (Exception e) {
            System.err.println("Error parsing DV: " + e.getMessage());
        }
    }

    private void handlePacket(DatagramPacket packet, DatagramSocket socket) {
        try {
            String msg = new String(packet.getData(), 0, packet.getLength());

            // Differentiate between Routing Updates and User Packets
            if (msg.startsWith("DV:")) {
                handleDVUpdate(msg);
            } else {
                Frame frame = new Frame(msg);
                handleUserPacket(frame, socket);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleUserPacket(Frame frame, DatagramSocket socket) {
        try {
            if (!frame.dst.equals(me.id)) return;

            String dstSubnet = frame.dstIP.substring(0, frame.dstIP.indexOf('.'));
            RoutingEntry route = routingTable.get(dstSubnet);

            if (route == null) {
                System.out.println("[" + me.id + "] No route to " + dstSubnet + ". Dropped.");
                return;
            }

            Device nextHop = findNeighbor(route.nextHop);
            if (nextHop == null) return;

            // Determine if next hop is a router or a switch (host)
            String newDstMAC = route.nextHop.startsWith("R") ? route.nextHop : extractId(frame.dstIP);

            Frame outFrame = new Frame(
                    me.id + ":" + newDstMAC + ":" +
                            frame.srcIP + ":" + frame.dstIP + ":" + frame.payload
            );

            byte[] data = outFrame.toString().getBytes();
            DatagramPacket outPacket = new DatagramPacket(
                    data, data.length, InetAddress.getByName(nextHop.ip), nextHop.port
            );
            socket.send(outPacket);

            System.out.println("[" + me.id + "] FORWARDED " + frame.payload + " to " + route.nextHop);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private void broadcastDistanceVector() {
        Map<String, Integer> myDV = new HashMap<>();
        for (Map.Entry<String, RoutingEntry> e : routingTable.entrySet()) {
            myDV.put(e.getKey(), e.getValue().cost);
        }

        String dvMessage = serializeDV(me.id, myDV);
        byte[] data = dvMessage.getBytes();

        for (Device neighbor : neighbors) {
            // Try-with-resources handles closing the socket to prevent leaks
            try (DatagramSocket sendSocket = new DatagramSocket()) {
                DatagramPacket packet = new DatagramPacket(
                        data, data.length, InetAddress.getByName(neighbor.ip), neighbor.port
                );
                sendSocket.send(packet);
            } catch (Exception e) {
                System.err.println("Error sending DV to " + neighbor.id);
            }
        }
    }

    private String serializeDV(String routerId, Map<String, Integer> dv) {
        StringBuilder sb = new StringBuilder("DV:");
        sb.append(routerId).append(":");
        for (Map.Entry<String, Integer> entry : dv.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append(",");
        }
        return sb.toString();
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