public class Frame {
    public int type;       // 0 = DATA, 1 = ROUTING
    public String src;     // virtual source MAC (device ID)
    public String dst;     // virtual destination MAC (device ID)
    public String srcIP;   // virtual source IP
    public String dstIP;   // virtual destination IP
    public String payload; // message or routing data

    public Frame(String raw) {
        // Updated to split into 6 parts to accommodate the 'type' flag
        String[] parts = raw.split(":", 6);
        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid frame (expected 6 fields): " + raw);
        }
        this.type    = Integer.parseInt(parts[0]);
        this.src     = parts[1];
        this.dst     = parts[2];
        this.srcIP   = parts[3];
        this.dstIP   = parts[4];
        this.payload = parts[5];
    }

    @Override
    public String toString() {
        return type + ":" + src + ":" + dst + ":" + srcIP + ":" + dstIP + ":" + payload;
    }
}