public class Frame {
    public String src;     // virtual source MAC (device ID)
    public String dst;     // virtual destination MAC (device ID)
    public String srcIP;   // virtual source IP
    public String dstIP;   // virtual destination IP
    public String payload; // message

    public Frame(String raw) {
        String[] parts = raw.split(":", 5); // limit 5 so payload can safely contain colons
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid frame (expected 5 fields): " + raw);
        }
        this.src     = parts[0];
        this.dst     = parts[1];
        this.srcIP   = parts[2];
        this.dstIP   = parts[3];
        this.payload = parts[4];
    }

    @Override
    public String toString() {
        return src + ":" + dst + ":" + srcIP + ":" + dstIP + ":" + payload;
    }
}