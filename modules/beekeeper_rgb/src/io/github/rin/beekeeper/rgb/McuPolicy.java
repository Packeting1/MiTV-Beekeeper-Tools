package io.github.rin.beekeeper.rgb;

/** Exact allowlist; a matching MCU version alone does not identify compatible hardware. */
public final class McuPolicy {
    public static final String SYSTEM="OS3.0.109.0.UFFMAMN";
    public static final String ORIGINAL="c78ea17cfe2c5278964e4cb57232db19fca1414b4fc5dcdb1df89faf8376145d";
    public static final String CUSTOM="69f4c469f391dabeef52489d01b3c41fbe7fe16297b4ebea144e0afdef793b5f";
    public static final String LIBRARY="5aac620745177370f59b0627e9b2f75dd95be2a4cf428d2658f8b5b7e44b4f11";
    public static final String FINGERPRINT="xiaomi/beekeeper/beekeeper:14/UQ1A.240105.004.A1/OS3.0.109.0.UFFMAMN:user/release-keys";
    public static void check(String device,String product,String system,String sdk,String fingerprint,String vendor,String library) {
        if(!"beekeeper".equals(device)||!"otfcn1n".equals(product)||!SYSTEM.equals(system)||!"34".equals(sdk)
                ||!FINGERPRINT.equals(fingerprint)||!ORIGINAL.equals(vendor)||!LIBRARY.equals(library))
            throw new IllegalArgumentException("机型、系统版本或原版文件校验不匹配，禁止刷写");
    }
    public static boolean ready(long armed,long now) {return armed>0&&now>=armed&&now-armed>=10000&&now-armed<=300000;}
    private McuPolicy(){}
}
