public class Frame {
    public String type;    // "DATA" or "DV"
    public String src;
    public String dst;
    public String srcIP;
    public String dstIP;
    public String payload;

    public Frame(String raw) {
        String[] parts = raw.split(":", 6); // now 6 parts

        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid frame (expected 6 fields): " + raw);
        }

        this.type    = parts[0];
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