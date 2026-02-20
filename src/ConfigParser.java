import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

public class ConfigParser {
    public static Config parse(String filename, String myId) {
        Map<String, Device> devices = new HashMap<>();
        List<String[]> links = new ArrayList<>();
        //Determine what line we are reading
        boolean readingDevices = true;
        boolean readingLinks = false;

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+"); //removes spaces between values

                if (parts.length == 2 && devices.size() > 0) {
                    readingDevices = false;
                    readingLinks = true;
                }

                if (readingDevices) {
                    if (parts.length >= 3) {
                        String id = parts[0];
                        String ip = parts[1];
                        int port = Integer.parseInt(parts[2]);
                        Device d = new Device(id, ip, port);
                        if (parts.length >= 4) d.virtualIP = parts[3];
                        if (parts.length >= 5) d.gateway = parts[4];
                        devices.put(id, d);
                    }
                }
                else if (readingLinks) {
                    if (parts.length != 2) continue;
                    links.add(new String[]{parts[0], parts[1]});
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Error reading config file", e);
        }

        if (!devices.containsKey(myId)) {
            throw new RuntimeException("Device ID not found in config: " + myId);
        }
        Device me = devices.get(myId);
        List<Device> neighbors = new ArrayList<>();
        for (String[] link : links) {
            if (link[0].equals(myId)) {
                neighbors.add(devices.get(link[1]));
            } else if (link[1].equals(myId)) {
                neighbors.add(devices.get(link[0]));
            }
        }
        return new Config(me, neighbors);
    }
}