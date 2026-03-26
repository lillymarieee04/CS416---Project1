import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class VirtualSwitch {

    public record Port(String ip, int port) {}

    private final String myId;
    private final String configPath;

    private final Map<String, Port> devices = new HashMap<>();
    private final Map<String, List<String>> adj = new HashMap<>();
    private Port myPhysical;
    private final List<Port> neighbors = new ArrayList<>();

    private final Map<String, Port> switchTable = new HashMap<>();

    public VirtualSwitch(String myId, String configPath) {
        this.myId = myId;
        this.configPath = configPath;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java VirtualSwitch <SWITCH_ID> <CONFIG_FILE>");
            System.out.println("Example: java VirtualSwitch S1 config.txt");
            return;
        }

        String myId = args[0].trim();
        String configPath = args[1].trim();

        VirtualSwitch vs = new VirtualSwitch(myId, configPath);
        try {
            vs.loadConfig();
            vs.run();
        } catch (Exception e) {
            System.out.println("Switch " + myId + " error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadConfig() throws IOException {
        parseConfigFile(configPath);

        myPhysical = devices.get(myId);
        if (myPhysical == null) {
            throw new IllegalArgumentException("Config has no DEVICE entry for: " + myId);
        }

        List<String> neighborIds = adj.getOrDefault(myId, List.of());
        for (String nid : neighborIds) {
            Port p = devices.get(nid);
            if (p == null) throw new IllegalArgumentException("LINK references unknown DEVICE: " + nid);
            neighbors.add(p);
        }

        System.out.println("Switch " + myId + " starting at " + myPhysical.ip + ":" + myPhysical.port);
        System.out.println("Neighbors:");
        for (Port p : neighbors) System.out.println("  - " + p.ip + ":" + p.port);
        System.out.println();
    }

    private void run() throws IOException {
        DatagramSocket socket = new DatagramSocket(myPhysical.port);
        socket.setReuseAddress(true);

        byte[] buf = new byte[4096];

        while (true) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            socket.receive(pkt);

            String frameStr = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
            Port ingress = resolveIngress(pkt);

            handleFrame(frameStr, ingress, socket);
        }
    }


    private Port resolveIngress(DatagramPacket pkt) {
        String srcIp = pkt.getAddress().getHostAddress();
        int srcPort = pkt.getPort();


        for (Port n : neighbors) {
            if (n.ip.equals(srcIp) && n.port == srcPort) return n;
        }

        for (Port n : neighbors) {
            if (n.ip.equals(srcIp)) return n;
        }

        return new Port(srcIp, srcPort);
    }

    private void handleFrame(String frameStr, Port ingress, DatagramSocket socket) throws IOException {
        String[] parts = frameStr.split(":", 5);
        if (parts.length < 3) {
            System.out.println("[" + myId + "] Dropping malformed frame: " + frameStr);
            return;
        }

        String srcMac = parts[0].trim();
        String dstMac = parts[1].trim();
        String dstVip = (parts.length >= 4) ? parts[3].trim() : "?";
        System.out.println("[" + myId + "] received frame: " + srcMac + " -> " + dstMac + " (dstIP=" + dstVip + ")");

        Port old = switchTable.put(srcMac, ingress);
        boolean isNew = (old == null);
        boolean moved = (old != null && !old.equals(ingress));
        if (isNew || moved) printSwitchTable();

        Port outPort = switchTable.get(dstMac);

        if (outPort != null) {

            if (!outPort.equals(ingress)) sendFrame(frameStr, outPort, socket);
        } else {

            for (Port p : neighbors) {
                if (!p.equals(ingress)) sendFrame(frameStr, p, socket);
            }
        }
    }

    private void sendFrame(String frameStr, Port target, DatagramSocket socket) throws IOException {
        byte[] data = frameStr.getBytes(StandardCharsets.UTF_8);
        InetAddress addr = InetAddress.getByName(target.ip);
        DatagramPacket out = new DatagramPacket(data, data.length, addr, target.port);
        socket.send(out);
    }

    private void printSwitchTable() {
        System.out.println("=== Switch Table (" + myId + ") ===");
        if (switchTable.isEmpty()) {
            System.out.println("(empty)");
        } else {
            List<String> macs = new ArrayList<>(switchTable.keySet());
            Collections.sort(macs);
            for (String mac : macs) {
                Port p = switchTable.get(mac);
                System.out.println(mac + " -> " + p.ip + ":" + p.port);
            }
        }
        System.out.println("=========================\n");
    }

    private void parseConfigFile(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            int lineno = 0;

            while ((line = br.readLine()) != null) {
                lineno++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] t = line.split("\\s+");
                if (t.length == 0) continue;

                String kind = t[0].toUpperCase(Locale.ROOT);

                switch (kind) {
                    case "DEVICE" -> {
                        if (t.length < 4) throw new IllegalArgumentException("Bad DEVICE line at " + lineno + ": " + line);
                        String id = t[1];
                        String ip = t[2];
                        int port = Integer.parseInt(t[3]);
                        devices.put(id, new Port(ip, port));
                        adj.putIfAbsent(id, new ArrayList<>());
                    }
                    case "LINK" -> {
                        if (t.length < 3) throw new IllegalArgumentException("Bad LINK line at " + lineno + ": " + line);
                        String a = t[1];
                        String b = t[2];
                        adj.putIfAbsent(a, new ArrayList<>());
                        adj.putIfAbsent(b, new ArrayList<>());
                        adj.get(a).add(b);
                        adj.get(b).add(a);
                    }
                    case "VIRTUAL_IP", "GATEWAY", "SUBNET" -> {
                        // ignore entries not needed by switch
                    }
                    default -> throw new IllegalArgumentException("Unknown config entry at " + lineno + ": " + line);
                }
            }
        }
    }
}