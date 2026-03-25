import java.util.List;
import java.util.Map;

public class Config {
    public Device device; //stores device info
    public List<Device> neighbors; //stores its connections

    public Map<String, String> subnetMap;

    //constructor
    public Config (Device device, List<Device> neighbors) {
        this.device = device;
        this.neighbors = neighbors;
    }
}