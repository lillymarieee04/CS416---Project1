
public class Frame {
    public String src;
    public String dst;
    public String payload;

    public Frame(String raw) {
        String[] parts = raw.split(":", 3);
        this.src = parts[0];
        this.dst = parts[1];
        this.payload = parts[2];
    }

    @Override
    public String toString() {
        return src + ":" + dst + ":" + payload;
    }
}
