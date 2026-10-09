package ww86.hoconfmt.java

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotlinInteropTest {

    @Test
    fun `when over the mirror is checked for exhaustiveness`() {
        val verdict: Verdict = HoconFmt.check("a   =   1\n")

        // A value-used when with no else: the Kotlin compiler accepts it only because the mirror
        // is sealed. Delete the `is Verdict.Refused` branch below and, from java-api/, run
        // `./gradlew compileTestKotlin`: it fails with "'when' expression must be exhaustive.
        // Add the 'is Refused' branch or an 'else' branch." (Kotlin 2.4.21, exit 1). The same
        // two-branch when over the core's own enum compiles and then throws
        // NoWhenBranchMatchedException at run time — the trap the mirror removes.
        fun shape(v: Verdict): String = when (v) {
            is Verdict.AlreadyFormatted -> "already"
            is Verdict.NeedsFormatting -> "needs: ${v.formatted().trim()}"
            is Verdict.Refused -> "refused: ${v.reason()}"
        }
        assertEquals("needs: a = 1", shape(verdict))
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
