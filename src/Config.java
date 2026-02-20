import java.util.List;

public class Config {
    public Device device; //stores device info
    public List<Device> neighbors; //stores it's connections

    //constructor
    public Config (Device device, List<Device> neighbors) {
        this.device = device;
        this.neighbors = neighbors;
    }
}