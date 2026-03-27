import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

public class ConfigParser {

    public static Config parse(String filename, String myId) {
        Map<String, Device> devices = new HashMap<>();
        List<String[]> links = new ArrayList<>();
        Map<String, String> subnetMap = new HashMap<>();

        boolean inDevices = true;
        boolean inLinks = false;
        boolean inSubnets = false;

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;

            while ((line = br.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+");

                // -------- SECTION DETECTION --------
                if (parts[0].equalsIgnoreCase("SUBNET")) {
                    inDevices = false;
                    inLinks = false;
                    inSubnets = true;
                }
                else if (parts.length == 2 && !inSubnets) {
                    inDevices = false;
                    inLinks = true;
                }

                // -------- SUBNET PARSING --------
                if (inSubnets && parts[0].equalsIgnoreCase("SUBNET")) {
                    String node1 = parts[1];
                    String node2 = parts[2];
                    String subnet = parts[3];

                    subnetMap.put(node1 + "-" + node2, subnet);
                    subnetMap.put(node2 + "-" + node1, subnet);
                    continue;
                }

                // -------- DEVICE PARSING --------
                if (inDevices) {
                    if (parts.length < 3) continue;

                    String id = parts[0];
                    String ip = parts[1];
                    int port = Integer.parseInt(parts[2]);

                    Device d = new Device(id, ip, port);

                    // Parse virtual IPs (netX.Y format)
                    List<String> vips = new ArrayList<>();
                    for (int i = 3; i < parts.length; i++) {
                        if (parts[i].contains(".")) {
                            vips.add(parts[i]);
                        }
                    }

                    if (!vips.isEmpty()) {
                        d.virtualIPs = vips;
                        d.virtualIP = vips.get(0); // for hosts compatibility
                    }

                    // Only hosts should have gateways
                    if (id.startsWith("A") || id.startsWith("B") || id.startsWith("C")) {
                        if (parts.length >= 5) {
                            d.gateway = parts[parts.length - 1];
                        }
                    }

                    devices.put(id, d);
                }


                else if (inLinks) {
                    if (parts.length == 2) {
                        links.add(new String[]{parts[0], parts[1]});
                    }
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Error reading config file", e);
        }

        if (!devices.containsKey(myId)) {
            throw new RuntimeException("Device ID not found: " + myId);
        }

        Device me = devices.get(myId);

        List<Device> neighbors = new ArrayList<>();
        for (String[] link : links) {
            if (link[0].equals(myId)) {
                Device neighbor = devices.get(link[1]);
                if (neighbor == null) {
                    throw new RuntimeException(
                            "Invalid link: " + link[0] + " -> " + link[1] +
                                    " (device not found in config)"
                    );
                }
                neighbors.add(neighbor);
            } else if (link[1].equals(myId)) {
                Device neighbor = devices.get(link[0]);
                if (neighbor == null) {
                    throw new RuntimeException(
                            "Invalid link: " + link[0] + " -> " + link[1] +
                                    " (device not found in config)"
                    );
                }
                neighbors.add(neighbor);
            }
        }

        Config config = new Config(me, neighbors);
        config.subnetMap = subnetMap;

        return config;
    }
}