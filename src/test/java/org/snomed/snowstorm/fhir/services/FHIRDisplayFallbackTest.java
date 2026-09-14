package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * $lookup must always answer with a display.
 *
 * `display` is 1..1 on the $lookup output, so answering without one is a conformance
 * violation rather than a cosmetic gap. getPreferredTerm nonetheless returned null whenever
 * the requested dialect matched no description: it tested only designations.getFirst() and
 * fell off the end. A concept in a partially translated edition -- or simply a request naming
 * a dialect the content does not carry -- came back with no display at all.
 *
 * The test data carries US English descriptions only, so asking for en-GB is exactly that
 * case: a well-formed request for a dialect with no PREFERRED description behind it.
 *
 * The assertions compare against the no-parameter answer rather than against a literal, so
 * they state the actual invariant -- naming an unsupported dialect must not COST you the
 * display -- without pinning the fixture's wording.
 *
 * The en-US case is a control. The fix adds fallback layers beneath the requested dialect,
 * and the way to get that wrong is to let a lower layer win over a dialect that did match.
 */
class FHIRDisplayFallbackTest extends AbstractFHIRTest {

	private String lookupDisplay(String languageSuffix) {
		Parameters parameters = getParameters(baseUrl + "/CodeSystem/$lookup?system=http://snomed.info/sct&code="
				+ sampleSCTID + languageSuffix);
		Type display = getProperty(parameters, "display");
		return display == null ? null : display.primitiveValue();
	}

	@Test
	void testUnsupportedDialectStillReturnsADisplay() {
		// Unpatched this is null: en-GB resolves to a real language refset, no description is
		// PREFERRED in it, and the lookup falls off the end of the loop.
		String display = lookupDisplay("&displayLanguage=en-GB");
		assertNotNull(display, "$lookup must not answer without a display; it is 1..1");
		assertFalse(display.isBlank(), "an empty display is the same conformance violation as none");
	}

	@Test
	void testUnsupportedDialectFallsBackToTheServerDefault() {
		// The invariant: naming a dialect the content does not carry must not cost the display.
		assertEquals(lookupDisplay(""), lookupDisplay("&displayLanguage=en-GB"),
				"an unsupported dialect must fall back, not blank out");
	}

	@Test
	void testSupportedDialectIsUnaffected() {
		// The control. Fallback layers added beneath the requested dialect must not override a
		// dialect that did match.
		String display = lookupDisplay("&displayLanguage=en-US");
		assertNotNull(display);
		assertEquals(lookupDisplay(""), display, "a dialect that matches must still win");
	}
}
