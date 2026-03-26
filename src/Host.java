import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Host {

    private final String id;
    private String myVip, gwVip, gwMac;
    private String swIp;
    private int myPort, swPort;

    private DatagramSocket socket;

    public Host(String id, String configPath) throws Exception {
        this.id = id.trim();
        loadConfig(configPath);

        socket = new DatagramSocket(myPort);
        socket.setReuseAddress(true);

        System.out.println("Host " + this.id + " on port " + myPort + ", vip=" + myVip + ", gw=" + gwVip);
        System.out.println("Switch " + swIp + ":" + swPort);
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: java Host <ID> <CONFIG>");
            return;
        }
        Host h = new Host(args[0], args[1]);
        h.startReceiver();
        h.startSender();
    }

    private void loadConfig(String path) throws Exception {
        Map<String, String> ip = new HashMap<>();
        Map<String, Integer> port = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, String> vip = new HashMap<>();
        Map<String, String> gw = new HashMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] t = line.split("\\s+");
                String kind = t[0].toUpperCase(Locale.ROOT);

                if (kind.equals("DEVICE")) {
                    ip.put(t[1], t[2]);
                    port.put(t[1], Integer.parseInt(t[3]));
                    adj.putIfAbsent(t[1], new ArrayList<>());
                } else if (kind.equals("LINK")) {
                    adj.putIfAbsent(t[1], new ArrayList<>());
                    adj.putIfAbsent(t[2], new ArrayList<>());
                    adj.get(t[1]).add(t[2]);
                    adj.get(t[2]).add(t[1]);
                } else if (kind.equals("VIRTUAL_IP")) {
                    if (t[1].equals(id)) vip.put(id, t[2]);
                } else if (kind.equals("GATEWAY")) {
                    if (t[1].equals(id)) gw.put(id, t[2]);
                }
            }
        }

        Integer mp = port.get(id);
        if (mp == null) throw new IllegalArgumentException("Missing DEVICE for " + id);
        myPort = mp;

        myVip = vip.get(id);
        gwVip = gw.get(id);
        if (myVip == null) throw new IllegalArgumentException("Missing VIRTUAL_IP for " + id);
        if (gwVip == null) throw new IllegalArgumentException("Missing GATEWAY for " + id);

        gwMac = idOf(gwVip);

        List<String> neighbors = adj.getOrDefault(id, List.of());
        if (neighbors.isEmpty()) throw new IllegalArgumentException("No LINK neighbor for " + id);

        String swId = neighbors.get(0);
        swIp = ip.get(swId);
        Integer sp = port.get(swId);
        if (swIp == null || sp == null) throw new IllegalArgumentException("Switch DEVICE missing: " + swId);
        swPort = sp;
    }

    private void startReceiver() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (true) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);

                    String frame = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
                    String[] p = frame.split(":", 5);
                    if (p.length < 5) continue;

                    String dstMac = p[1].trim();
                    String srcVip = p[2].trim();
                    String msg = p[4];

                    if (dstMac.equals(id)) {
                        System.out.println("Message: \"" + msg + "\"  Source: " + srcVip);
                    } else {
                        System.out.println("[DEBUG] flooded frame ignored (dstMAC=" + dstMac + ")");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void startSender() throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("Destination virtual IP (e.g., net3.D): ");
            String dstVip = sc.nextLine().trim();
            System.out.print("Message: ");
            String msg = sc.nextLine().trim();
            String dstMac;
            if (subnetOf(dstVip).equals(subnetOf(myVip))) {
                dstMac = idOf(dstVip);
            } else {
                dstMac = gwMac;
            }

            String frame = id + ":" + dstMac + ":" + myVip + ":" + dstVip + ":" + msg;


            System.out.println("[SEND] " + frame);

            byte[] data = frame.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(swIp), swPort));
        }
    }

    private static String idOf(String vip) {
        int dot = vip.lastIndexOf('.');
        return dot < 0 ? vip : vip.substring(dot + 1);
    }

    private static String subnetOf(String vip) {
        int dot = vip.indexOf('.');
        return dot < 0 ? vip : vip.substring(0, dot);
    }
}