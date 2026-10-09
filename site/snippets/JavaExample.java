import eu.ww86.hoconfmt.java.HoconFmt;
import eu.ww86.hoconfmt.java.Verdict;

public final class JavaExample {
    public static void main(String[] args) {
        Verdict verdict = HoconFmt.check("app.port=8080");
        System.out.println(verdict);
    }
}
