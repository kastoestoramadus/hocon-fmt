import ww86.hocon_fmt.java.HoconFmt;
import ww86.hocon_fmt.java.Verdict;

public final class JavaExample {
    public static void main(String[] args) {
        Verdict verdict = HoconFmt.check("app.port=8080");
        System.out.println(verdict);
    }
}
