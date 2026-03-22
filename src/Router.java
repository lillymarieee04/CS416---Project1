import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketPermission;
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
        // buildForwardingTable();
        initilzieSubnetMappings();
    }

    private void  initilzieSubnetMappings() {
        //Host 
        // if (me.id.equals("R1")) {
        // }
        linkToSubnet.put("R1-S1", "net1"); 
        linkToSubnet.put("S1-R1", "net1");

        linkToSubnet.put("R3-S2", "net2");
        linkToSubnet.put("S2-R3", "net2");

        linkToSubnet.put("R6-S3", "net3");
        linkToSubnet.put("S3-R6", "net3");


        //Transit subnets between routers 
        linkToSubnet.put("R1-R2", "net4");
        linkToSubnet.put("R2-R1", "net4");

        linkToSubnet.put("R1-R3", "net5");
        linkToSubnet.put("R3-R1", "net5");

        linkToSubnet.put("R2-R3", "net6");
        linkToSubnet.put("R3-R2", "net6");

        linkToSubnet.put("R2-R4", "net7");
        linkToSubnet.put("R4-R2", "net7");

        linkToSubnet.put("R3-R5", "net8");
        linkToSubnet.put("R5-R3", "net8");

        linkToSubnet.put("R4-R5", "net9");
        linkToSubnet.put("R5-R4", "net9");

        linkToSubnet.put("R4-R6", "net10");
        linkToSubnet.put("R6-R4", "net10");

        // else if (me.id.equals("R2")) {
        
       // }

        // System.out.println("Router " + me.id + " forwarding table:");
        // for (Map.Entry<String, String> e : forwardingTable.entrySet()) {
        //     System.out.println("  " + e.getKey() + " -> " + e.getValue());
        // }
        // System.out.println();
    }

    public void start() throws Exception {
        DatagramSocket socket = new DatagramSocket(me.port);
        System.out.println("Router " + me.id + " started (Distance Vector Routing) ");
        System.out.println("Ports: " + me.port);
        System.out.println();


        initializeRoutingTable();

        broadcastDistanceVector();

        startPeriodicUpdates();

        
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

    private void initializeRoutingTable() {
        System.out.println("[" + me.id + "] Initializing routing table...");

        for (Device neighbor : neighbors) {
            String linkId = me.id + "-" + neighbor.id;
            String subnet = linkToSubnet.get(linkId);

            if (subnet != null) {
                routingTable.put(subnet, new RoutingEntry(neighbor.id, 1));
                neighborCosts.put(neighbor.id, 1);
                System.out.println("[" + me.id + "] Added route to " + subnet
                        + " via " + neighbor.id + " (cost=1)");
            }
        }
        System.out.println();
        printRoutingTable();
    }


    private void broadcastDistanceVector(){
        Map<String, Integer> myDV = new HashMap<>();
        for (Map.Entry<String, RoutingEntry> e : routingTable.entrySet()) {
            myDV.put(e.getKey(), e.getValue().cost);
        }

        String dvMessage = serializeDV(me.id, myDV);

        for (Device neighbor : neighbors) {
            try {
                DatagramSocket socket = new DatagramSocket();
                byte[] data = dvMessage.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(neighbor.ip),
                        neighbor.port
                );
                socket.send(packet);
                System.out.println("[" + me.id + "] Sent DV to " + neighbor.id + "(subnets = " + myDV.size() + ") \n");
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