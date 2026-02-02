//A switch has multiple virtual ports, and each virtual port should be “named” using the
//IP address and the port number of the neighbor switch or host.

//For debugging purposes, the switch must print out the entire switch table every time a
//new entry is added
import java.awt.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.List;

public class Switch {

    private Device me;
    private List<Device> neighbors;
    private Map<String, Device> switchTable = new HashMap<>();

    public Switch(Config config) {
        this.me = config.device;
        this.neighbors = config.neighbors;
    }

    public void start() throws Exception {
        DatagramSocket socket = new DatagramSocket(me.port);
        System.out.println("Switch " + me.id + " listening on port " + me.port);

        byte[] buffer = new byte[1024];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String msg = new String(packet.getData(), 0, packet.getLength());
            Frame frame = new Frame(msg);

            Device incomingPort = findNeighbor(packet.getAddress().getHostAddress(),packet.getPort());
        }

        if (incomingPort == null){
            System.out.println("Port not recognized. Dropping Packet.");
            continue;
        }
    }
}
