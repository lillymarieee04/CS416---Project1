import java.util.ArrayList;
import java.util.List;

public class Device {
    public String id;
    public String ip;
    public int port;

    // Backward compatibility
    public String virtualIP;

    // Support multiple routers
    public List<String> virtualIPs = new ArrayList<>();

    public String gateway;

    public Device(String id, String ip, int port){
        this.id = id;
        this.ip = ip;
        this.port = port;
    }

    @Override
    public String toString() {
        return id + " (" + ip + ":" + port + ")";
    }
}