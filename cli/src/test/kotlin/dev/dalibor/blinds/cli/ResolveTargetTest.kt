package dev.dalibor.blinds.cli

import dev.dalibor.blinds.gateway.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveTargetTest {
    private val kitchen = Device(1, "Kitchen", 2, false)
    private val kitchen2 = Device(2, "Kitchen 2", 2, false)
    private val office = Device(12, "Office", 2, false)
    private val blinds = listOf(kitchen, kitchen2, office)

    private fun found(target: String) = (resolveTarget(blinds, target) as TargetMatch.Found).devices

    @Test fun allSelectsEveryBlind() = assertEquals(blinds, found("ALL"))

    @Test fun matchesById() = assertEquals(listOf(office), found("12"))

    @Test fun exactNameBeatsPrefix() = assertEquals(listOf(kitchen), found("kitchen"))

    @Test fun uniquePrefixMatches() = assertEquals(listOf(office), found("off"))

    @Test fun sharedPrefixIsAmbiguous() = assertTrue(resolveTarget(blinds, "kit") is TargetMatch.Ambiguous)

    @Test fun unknownNameMatchesNothing() = assertTrue(resolveTarget(blinds, "attic") is TargetMatch.None)

    @Test fun jsonEscapesQuotesAndControls() = assertEquals("a\\\"b\\\\c\\u000a", escapeJson("a\"b\\c\n"))
}
