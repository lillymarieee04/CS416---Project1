public class Device {
    public String id;
    public String ip;
    public int port;
    public String virtualIP;
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
