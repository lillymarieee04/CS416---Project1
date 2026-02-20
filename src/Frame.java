
public class Frame {
    public String src; //MAC
    public String dst; //MAC
    public String srcIP;
    public String dstIP;
    public String payload;

    public Frame(String raw) {
        String[] parts = raw.split(":", 5);
        this.src = parts[0];
        this.dst = parts[1];
        this.dstIP = parts[2];
        this.srcIP = parts[3];
        this.payload = parts[4];
    }

    @Override
    public String toString() {
        return src + ":" + dst + ":" + dstIP+ ":" +srcIP+ ":" +payload;
    }
}
