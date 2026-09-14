package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * $translate must support reverse=true.
 *
 * The parameter is defined by the R4 operation and was answered with notSupported, so a caller
 * holding a target-side code had no way to ask which source code maps to it -- despite the
 * server holding exactly the rows needed to answer, and answering the same question happily in
 * the other direction.
 *
 * Map SELECTION has to flip as well as group filtering, which is the part that is easy to miss:
 * filtering the groups of a map chosen by its SOURCE system yields "no suitable map found" with
 * the map sitting right there, because the map was never a candidate in the first place. The
 * reverse assertion below fails that way, not by returning a wrong code, if only half the flip
 * is implemented.
 *
 * The forward assertions are controls. reverse is threaded through map resolution as a
 * parameter, and touching resolution is how you break the direction that already worked.
 */
class FHIRTranslateReverseTest extends AbstractFHIRTest {

	private static final String SNOMED = "http://snomed.info/sct";
	private static final String ICD10 = "http://hl7.org/fhir/sid/icd-10";
	private static final String MAPPED_ICD_CODE = "A1.100";

	private Parameters translate(String query) {
		return getParameters(baseUrl + "/ConceptMap/$translate?" + query);
	}

	private Parameters translate(String query, int status, String bodyContains) {
		return getParameters(baseUrl + "/ConceptMap/$translate?" + query, status, bodyContains);
	}

	private String firstMatchCode(Parameters parameters) {
		return parameters.getParameter().stream()
				.filter(p -> "match".equals(p.getName()))
				.flatMap(p -> p.getPart().stream())
				.filter(part -> "concept".equals(part.getName()))
				.map(part -> ((org.hl7.fhir.r4.model.Coding) part.getValue()).getCode())
				.findFirst().orElse(null);
	}

	@Test
	void testForwardTranslateStillWorks() {
		// Control: the direction that already worked must keep working.
		Parameters parameters = translate("code=" + sampleSCTID + "&system=" + SNOMED + "&targetsystem=" + ICD10);
		assertTrue(parameters.getParameterBool("result"), "forward translate must still find the map");
		assertEquals(MAPPED_ICD_CODE, firstMatchCode(parameters));
	}

	@Test
	void testReverseTranslateFindsTheSourceCode() {
		// Before reverse support: refused outright with notSupported. While reverse support
		// resolved the SNOMED version from the supplied coding: a 500, because the coding
		// names ICD-10 and its getSnomedBranch() is null.
		Parameters parameters = translate("code=" + MAPPED_ICD_CODE + "&system=" + ICD10
				+ "&reverse=true");
		assertTrue(parameters.getParameterBool("result"),
				"reverse translate must find the map whose TARGET side carries this code");
		assertEquals(sampleSCTID, firstMatchCode(parameters));
	}

	@Test
	void testReverseTranslateNarrowedByTargetSystem() {
		// targetsystem names where the ANSWER should come from, which under reversal is the
		// map's source side. Constrained against the target side it contradicts the coding
		// clause -- both pinning the same field to a different system -- and selects no map at
		// all, reported as "No suitable map found" for a map that is sitting right there.
		Parameters parameters = translate("code=" + MAPPED_ICD_CODE + "&system=" + ICD10
				+ "&targetsystem=" + SNOMED + "&reverse=true");
		assertTrue(parameters.getParameterBool("result"),
				"narrowing a reverse translate by target system must still find the map");
		assertEquals(sampleSCTID, firstMatchCode(parameters));
	}

	@Test
	void testReverseTranslateOfAnUnmappedCodeFindsNothing() {
		// The control that stops "reverse" from becoming "match anything". A reverse lookup of
		// a code no map targets must report no result, not the first row it happened to load.
		Parameters parameters = translate("code=ZZ9.999&system=" + ICD10 + "&reverse=true");
		assertFalse(parameters.getParameterBool("result"),
				"a code that nothing maps to must not translate");
	}
}
