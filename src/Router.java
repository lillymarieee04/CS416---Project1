import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class VirtualRouter {

    record Port(String ip, int port) {}

    static class RouteEntry {
        int cost;
        String nextHopRouter;
        String outDeviceId;

        RouteEntry(int cost, String nextHopRouter, String outDeviceId) {
            this.cost = cost;
            this.nextHopRouter = nextHopRouter;
            this.outDeviceId = outDeviceId;
        }
    }

    private final String myId;
    private final String configPath;

    private final Map<String, Port> devices = new HashMap<>();
    private final Map<String, List<String>> adj = new HashMap<>();
    private final Map<String, List<String>> vipByDevice = new HashMap<>();
    private final Map<String, String> hostGateway = new HashMap<>();

    private final Set<String> myDirectSubnets = new HashSet<>();
    private final Set<String> routerNeighbors = new HashSet<>();
    private final Map<String, RouteEntry> routingTable = new HashMap<>();
    private final Map<String, String> sharedSubnetWithRouter = new HashMap<>();

    private Port me;
    private DatagramSocket socket;

    public VirtualRouter(String myId, String configPath) {
        this.myId = myId.trim();
        this.configPath = configPath.trim();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: java VirtualRouter <ROUTER_ID> <CONFIG_FILE>");
            return;
        }

        VirtualRouter r = new VirtualRouter(args[0], args[1]);
        r.loadConfig();
        r.initDynamicRouting();
        r.startPeriodicUpdates();
        r.run();
    }

    private void loadConfig() throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(configPath))) {
            String line;
            int lineNo = 0;

            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] t = line.split("\\s+");
                String kind = t[0].toUpperCase(Locale.ROOT);

                switch (kind) {
                    case "DEVICE" -> {
                        if (t.length < 4) {
                            throw new IllegalArgumentException("Bad DEVICE line at " + lineNo + ": " + line);
                        }
                        String id = t[1];
                        String ip = t[2];
                        int port = Integer.parseInt(t[3]);
                        devices.put(id, new Port(ip, port));
                        adj.putIfAbsent(id, new ArrayList<>());
                        vipByDevice.putIfAbsent(id, new ArrayList<>());
                    }
                    case "LINK" -> {
                        if (t.length < 3) {
                            throw new IllegalArgumentException("Bad LINK line at " + lineNo + ": " + line);
                        }
                        String a = t[1];
                        String b = t[2];
                        adj.putIfAbsent(a, new ArrayList<>());
                        adj.putIfAbsent(b, new ArrayList<>());
                        adj.get(a).add(b);
                        adj.get(b).add(a);
                    }
                    case "VIRTUAL_IP" -> {
                        if (t.length < 3) {
                            throw new IllegalArgumentException("Bad VIRTUAL_IP line at " + lineNo + ": " + line);
                        }
                        String id = t[1];
                        String vip = t[2];
                        vipByDevice.putIfAbsent(id, new ArrayList<>());
                        vipByDevice.get(id).add(vip);
                    }
                    case "GATEWAY" -> {
                        if (t.length < 3) {
                            throw new IllegalArgumentException("Bad GATEWAY line at " + lineNo + ": " + line);
                        }
                        hostGateway.put(t[1], t[2]);
                    }
                    default -> {
                    }
                }
            }
        }

        me = devices.get(myId);
        if (me == null) {
            throw new IllegalArgumentException("Missing DEVICE for " + myId);
        }

        socket = new DatagramSocket(me.port);
        socket.setReuseAddress(true);

        System.out.println("Router " + myId + " listening on " + me.ip + ":" + me.port);
        System.out.println("Neighbors: " + adj.getOrDefault(myId, List.of()));
        System.out.println();
    }

    private void initDynamicRouting() {
        for (String vip : vipByDevice.getOrDefault(myId, List.of())) {
            myDirectSubnets.add(subnetOf(vip));
        }

        for (String neighbor : adj.getOrDefault(myId, List.of())) {
            if (isRouter(neighbor)) {
                routerNeighbors.add(neighbor);

                String shared = findSharedSubnet(myId, neighbor);
                if (shared != null) {
                    sharedSubnetWithRouter.put(neighbor, shared);
                }
            }
        }


        for (String subnet : myDirectSubnets) {
            String outDevice = findOutDeviceForDirectSubnet(subnet);
            routingTable.put(subnet, new RouteEntry(0, null, outDevice));
        }

        System.out.println("[" + myId + "] Directly connected subnets: " + sorted(myDirectSubnets));
        System.out.println("[" + myId + "] Router neighbors: " + sorted(routerNeighbors));
        printRoutingTable();


        broadcastDistanceVector();
    }

    private void startPeriodicUpdates() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    broadcastDistanceVector();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void run() throws Exception {
        byte[] buf = new byte[8192];

        while (true) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            socket.receive(pkt);

            String frame = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
            String[] p = frame.split(":", 5);
            if (p.length < 5) continue;

            String srcMac = p[0].trim();
            String dstMac = p[1].trim();
            String srcVip = p[2].trim();
            String dstVip = p[3].trim();
            String msg = p[4];

            if (!dstMac.equals(myId)) {
                continue;
            }

            if (isRoutingPacket(srcVip, dstVip, msg)) {
                handleRoutingPacket(srcMac, msg);
            } else {
                handleUserPacket(srcMac, dstMac, srcVip, dstVip, msg);
            }
        }
    }

    private void handleRoutingPacket(String srcMac, String msg) throws Exception {
        if (!isRouter(srcMac)) return;

        boolean changed = updateFromNeighbor(srcMac, msg);

        System.out.println("[" + myId + "] received routing update from " + srcMac);
        if (changed) {
            printRoutingTable();
            broadcastDistanceVector();
        }
    }

    private void handleUserPacket(String srcMac, String dstMac, String srcVip, String dstVip, String msg) throws Exception {
        System.out.println("[" + myId + "] RECEIVED");
        printFrame(srcMac, dstMac, srcVip, dstVip, msg);

        String dstSubnet = subnetOf(dstVip);
        RouteEntry entry = routingTable.get(dstSubnet);

        if (entry == null || entry.outDeviceId == null) {
            System.out.println("[" + myId + "] DROP (no route)\n");
            return;
        }

        Port outNeighbor = devices.get(entry.outDeviceId);
        if (outNeighbor == null) {
            System.out.println("[" + myId + "] DROP (missing out device " + entry.outDeviceId + ")\n");
            return;
        }

        String nextHopMac;
        if (entry.nextHopRouter == null) {
            nextHopMac = idOf(dstVip);
        } else {
            nextHopMac = entry.nextHopRouter;
        }

        String outFrame = myId + ":" + nextHopMac + ":" + srcVip + ":" + dstVip + ":" + msg;

        System.out.println("[" + myId + "] FORWARDED");
        printFrame(myId, nextHopMac, srcVip, dstVip, msg);
        System.out.println();

        send(outFrame, outNeighbor);
    }

    private synchronized boolean updateFromNeighbor(String neighborRouter, String msg) {
        Map<String, Integer> neighborDV = parseDvMessage(msg);
        boolean changed = false;

        for (Map.Entry<String, Integer> e : neighborDV.entrySet()) {
            String subnet = e.getKey();
            int neighborCost = e.getValue();

            if (neighborCost >= 999999) continue;

            int candidate = neighborCost + 1;

            RouteEntry current = routingTable.get(subnet);

            if (myDirectSubnets.contains(subnet)) {
                continue;
            }

            if (current == null || candidate < current.cost ||
                    (current.nextHopRouter != null && current.nextHopRouter.equals(neighborRouter) && candidate != current.cost)) {

                String outDevice = neighborRouter;
                routingTable.put(subnet, new RouteEntry(candidate, neighborRouter, outDevice));
                changed = true;
            }
        }

        return changed;
    }

    private synchronized void broadcastDistanceVector() {
        for (String neighbor : routerNeighbors) {
            try {
                Port target = devices.get(neighbor);
                String sharedSubnet = sharedSubnetWithRouter.getOrDefault(neighbor, "ROUTING");
                String srcVip = "ROUTING." + myId;
                String dstVip = sharedSubnet + "." + neighbor;
                String msg = buildDvMessage();

                String frame = myId + ":" + neighbor + ":" + srcVip + ":" + dstVip + ":" + msg;
                send(frame, target);
            } catch (Exception e) {
                System.out.println("[" + myId + "] failed to send DV to " + neighbor + ": " + e.getMessage());
            }
        }
    }

    private String buildDvMessage() {
        List<String> subnets = new ArrayList<>(routingTable.keySet());
        Collections.sort(subnets);

        StringBuilder sb = new StringBuilder();
        sb.append("DV");
        for (String subnet : subnets) {
            RouteEntry entry = routingTable.get(subnet);
            sb.append("|").append(subnet).append("=").append(entry.cost);
        }
        return sb.toString();
    }

    private Map<String, Integer> parseDvMessage(String msg) {
        Map<String, Integer> dv = new HashMap<>();
        if (msg == null || !msg.startsWith("DV")) return dv;

        String[] parts = msg.split("\\|");
        for (int i = 1; i < parts.length; i++) {
            String token = parts[i].trim();
            int eq = token.indexOf('=');
            if (eq < 0) continue;

            String subnet = token.substring(0, eq).trim();
            String costStr = token.substring(eq + 1).trim();

            try {
                int cost = Integer.parseInt(costStr);
                dv.put(subnet, cost);
            } catch (NumberFormatException ignored) {
            }
        }
        return dv;
    }

    private boolean isRoutingPacket(String srcVip, String dstVip, String msg) {
        return srcVip.startsWith("ROUTING.") || dstVip.startsWith("ROUTING.") || msg.startsWith("DV");
    }

    private void send(String frame, Port target) throws Exception {
        byte[] data = frame.getBytes(StandardCharsets.UTF_8);
        InetAddress addr = InetAddress.getByName(target.ip);
        socket.send(new DatagramPacket(data, data.length, addr, target.port));
    }

    private String findOutDeviceForDirectSubnet(String subnet) {

        for (String neighbor : adj.getOrDefault(myId, List.of())) {
            if (!isRouter(neighbor)) continue;
            for (String vip : vipByDevice.getOrDefault(neighbor, List.of())) {
                if (subnetOf(vip).equals(subnet)) {
                    return neighbor;
                }
            }
        }


        for (String neighbor : adj.getOrDefault(myId, List.of())) {
            if (!isSwitch(neighbor)) continue;
            if (switchTouchesSubnet(neighbor, subnet)) {
                return neighbor;
            }
        }


        for (String neighbor : adj.getOrDefault(myId, List.of())) {
            if (!isRouter(neighbor)) return neighbor;
        }

        return null;
    }

    private boolean switchTouchesSubnet(String switchId, String subnet) {
        for (String dev : adj.getOrDefault(switchId, List.of())) {
            if (dev.equals(myId)) continue;

            for (String vip : vipByDevice.getOrDefault(dev, List.of())) {
                if (subnetOf(vip).equals(subnet)) {
                    return true;
                }
            }

            String gwVip = hostGateway.get(dev);
            if (gwVip != null && subnetOf(gwVip).equals(subnet)) {
                return true;
            }
        }
        return false;
    }

    private String findSharedSubnet(String routerA, String routerB) {
        Set<String> aSubnets = new HashSet<>();
        for (String vip : vipByDevice.getOrDefault(routerA, List.of())) {
            aSubnets.add(subnetOf(vip));
        }

        for (String vip : vipByDevice.getOrDefault(routerB, List.of())) {
            String subnet = subnetOf(vip);
            if (aSubnets.contains(subnet)) {
                return subnet;
            }
        }
        return null;
    }

    private static boolean isRouter(String id) {
        return id != null && id.toUpperCase(Locale.ROOT).startsWith("R");
    }

    private static boolean isSwitch(String id) {
        return id != null && id.toUpperCase(Locale.ROOT).startsWith("S");
    }

    private static String subnetOf(String vip) {
        int dot = vip.indexOf('.');
        return (dot < 0) ? vip : vip.substring(0, dot);
    }

    private static String idOf(String vip) {
        int dot = vip.lastIndexOf('.');
        return (dot < 0) ? vip : vip.substring(dot + 1);
    }

    private static List<String> sorted(Collection<String> c) {
        List<String> out = new ArrayList<>(c);
        Collections.sort(out);
        return out;
    }

    private void printRoutingTable() {
        System.out.println("=== Routing Table (" + myId + ") ===");
        List<String> keys = new ArrayList<>(routingTable.keySet());
        Collections.sort(keys);

        for (String subnet : keys) {
            RouteEntry e = routingTable.get(subnet);
            String nextHop = (e.nextHopRouter == null) ? "DIRECT" : e.nextHopRouter;
            System.out.println(subnet + " -> cost=" + e.cost + ", nextHop=" + nextHop + ", out=" + e.outDeviceId);
        }
        System.out.println("============================\n");
    }

    private void printFrame(String srcMac, String dstMac, String srcVip, String dstVip, String msg) {
        System.out.println("  srcMAC=" + srcMac);
        System.out.println("  dstMAC=" + dstMac);
        System.out.println("  srcIP =" + srcVip);
        System.out.println("  dstIP =" + dstVip);
        System.out.println("  msg   =" + msg);
    }
}