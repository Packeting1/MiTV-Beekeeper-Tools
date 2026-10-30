import io.github.rin.beekeeper.rgb.RgbColor;

public class RgbColorTest {
    public static void main(String[] args) {
        check(RgbColor.parse("#66ffcc") == 0x66ffcc);
        check(RgbColor.parse("66FFCC") == 0x66ffcc);
        check(RgbColor.parse("  #000000  ") == 0);
        check(RgbColor.parse("#FFFFFF") == 0xffffff);
        check(RgbColor.format(0x66ffcc).equals("#66FFCC"));
        check(RgbColor.encode(0x66ffcc) == 0x0166ffcc);
        for (String bad : new String[]{null, "", "#123", "#12345678", "ggffff", "-12345", "#00000g", "##000000"}) {
            try { RgbColor.parse(bad); throw new AssertionError("accepted: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("RGB parsing, normalization, bounds, and protocol tests passed");
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
}
