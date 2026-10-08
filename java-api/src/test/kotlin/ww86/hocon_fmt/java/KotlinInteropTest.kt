package ww86.hocon_fmt.java

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotlinInteropTest {

    @Test
    fun `when over the verdict needs no else`() {
        val verdict: Verdict = HoconFmt.check("a = 1\n")

        var shaped = ""
        when (verdict) {
            is Verdict.AlreadyFormatted -> shaped = "already"
            is Verdict.NeedsFormatting -> shaped = "needs: ${verdict.formatted().trim()}"
            is Verdict.Refused -> shaped = "refused: ${verdict.reason()}"
        }
        assertEquals("needs: a: 1", shaped)
    }

    @Test
    fun `the check result is non-null, not a platform type`() {
        // Compiles because the JSpecify @NullMarked package makes the return strictly Verdict;
        // a nullable Verdict? would refuse this assignment.
        val verdict: Verdict = HoconFmt.check("a: 1\n")
        // The `!!` below is redundant exactly because the type is already non-null, and the Kotlin
        // compiler says so in the build output.
        val asserted = HoconFmt.check("a: 1\n")!!
        assertEquals(verdict, asserted)
    }
}
