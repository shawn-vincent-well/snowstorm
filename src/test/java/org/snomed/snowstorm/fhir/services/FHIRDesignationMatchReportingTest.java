package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When `$expand?filter=` matches a concept through a designation, the expansion has to say so.
 *
 * Once the filter searches designations as well as display, a designation-only match comes back
 * labelled with a display that does not contain what the caller typed. On a fee schedule,
 * filtering for "weekdays" returns dozens of concepts and the matched
 * "Travel Premium - Weekdays Daytime (07:00-17:00)" is never shown. A picker cannot usefully
 * render that, and a client retrieving the concept has lost the very text it searched for.
 *
 * ValueSet.expansion.contains.designation is populated with the designation(s) that matched.
 * Both filter paths are covered here:
 *   1. the Elasticsearch-backed expansion, reported by designationMatchPredicateForIndexedFilter
 *   2. the in-memory tx-resource overlay expansion, reported by
 *      designationMatchPredicateForInlineFilter
 * The two paths do not filter alike, and testInlineAndIndexedFilterSemanticsDiffer pins down the
 * difference that makes one shared reporter wrong.
 *
 * The negative cases below are the load-bearing half: "always attach the designations" would
 * satisfy the positive assertions just as well and would be wrong, because it would claim a
 * designation drove a match that the display drove.
 */
class FHIRDesignationMatchReportingTest extends AbstractFHIRTest {

	private static final String CS_URL = "http://example.com/fhir/CodeSystem/designation-match-reporting-test";
	private static final String IMPLICIT_VS = CS_URL + "?fhir_vs";
	private static final String OVERLAY_CS_URL = "http://example.com/fhir/CodeSystem/designation-match-reporting-overlay";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private FHIRCodeSystemVersion codeSystemVersion;

	// Three concepts modelled on real fee-schedule rows:
	//  - H101 carries TWO designations of which one matches "weekdays", which is what makes
	//    "only the matched designation" testable at all.
	//  - A005 is a second designation-only match, so one passing case is not a fluke.
	//  - J002 is the negative: "arthrogram" is in its display and in no designation, so it must
	//    come back with no designation attached.
	//
	// Designations carry use = SNOMED 900000000000013009 (Synonym) with no language. That shape
	// matters: the display-promotion logic in setDisplayAndDesignations can lift a
	// language-tagged designation into the display, so a differently shaped designation would
	// legitimately become the display and the assertions below would quietly be testing display
	// selection rather than match reporting.
	private static final String CODE_SYSTEM_JSON = """
			{
				"resourceType": "CodeSystem",
				"url": "%s",
				"version": "1",
				"name": "DesignationMatchReportingTest",
				"status": "draft",
				"content": "complete",
				"concept": [
					{
						"code": "H101",
						"display": "Travel Premium",
						"designation": [
							{
								"use": {
									"system": "http://snomed.info/sct",
									"code": "900000000000013009",
									"display": "Synonym"
								},
								"value": "Travel Premium - Weekdays Daytime (07:00-17:00)"
							},
							{
								"use": {
									"system": "http://snomed.info/sct",
									"code": "900000000000013009",
									"display": "Synonym"
								},
								"value": "Travel Premium - Nights and Holidays"
							}
						]
					},
					{
						"code": "A005",
						"display": "Consultation",
						"designation": [
							{
								"use": {
									"system": "http://snomed.info/sct",
									"code": "900000000000013009",
									"display": "Synonym"
								},
								"value": "Office assessment - general"
							}
						]
					},
					{
						"code": "J002",
						"display": "Arthrogram, tenogram or bursogram unilateral",
						"designation": [
							{
								"use": {
									"system": "http://snomed.info/sct",
									"code": "900000000000013009",
									"display": "Synonym"
								},
								"value": "Bronchogram - unilateral"
							}
						]
					}
				]
			}""";

	@BeforeEach
	void testSetup() throws ServiceException {
		CodeSystem codeSystem = fhirJsonParser.parseResource(CodeSystem.class, CODE_SYSTEM_JSON.formatted(CS_URL));
		codeSystemVersion = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), codeSystemVersion);
	}

	@AfterEach
	void testAfter() {
		codeSystemService.deleteCodeSystemVersion(codeSystemVersion);
	}

	@Test
	void testDesignationOnlyMatchCarriesTheMatchedText() {
		// This is the test that goes red with the production change reverted: the designation
		// search already returns H101, but with an empty designation list, so the caller sees
		// "Travel Premium" and no "weekdays" anywhere in the response.
		List<ValueSet.ValueSetExpansionContainsComponent> contains = expand("weekdays");
		assertEquals(1, contains.size(), "filter=weekdays must find H101 through its designation");
		ValueSet.ValueSetExpansionContainsComponent h101 = contains.get(0);
		assertEquals("H101", h101.getCode());
		assertEquals(1, h101.getDesignation().size(), "the expansion must report the designation that matched");
		assertEquals("Travel Premium - Weekdays Daytime (07:00-17:00)", h101.getDesignation().get(0).getValue());

		// A second designation-only match, on a different concept and a different word.
		contains = expand("assessment");
		assertEquals(1, contains.size());
		assertEquals("A005", contains.get(0).getCode());
		assertEquals(1, contains.get(0).getDesignation().size());
		assertEquals("Office assessment - general", contains.get(0).getDesignation().get(0).getValue());
	}

	@Test
	void testDisplayIsNotReplacedByTheMatchedDesignation() {
		// Some servers substitute the matched synonym into `display`. This must not: the code
		// system's own display is the label, which is generally why alternates were loaded as
		// designations rather than as displays. Substituting here would be the synonym hijack
		// that the designation `use` ranking exists to prevent.
		ValueSet.ValueSetExpansionContainsComponent h101 = expand("weekdays").get(0);
		assertEquals("Travel Premium", h101.getDisplay(), "the concept's own display must survive a designation match");
		assertNotEquals(h101.getDisplay(), h101.getDesignation().get(0).getValue());
	}

	@Test
	void testOnlyTheMatchedDesignationIsReported() {
		// H101 has two designations. Reporting both would answer a question nobody asked and would
		// stop the designation list meaning "this is what you typed that got you here".
		ValueSet.ValueSetExpansionContainsComponent h101 = expand("weekdays").get(0);
		assertEquals(1, h101.getDesignation().size(), "only the designation that matched, not the whole set");
		assertFalse(h101.getDesignation().stream().anyMatch(d -> "Travel Premium - Nights and Holidays".equals(d.getValue())),
				"the designation that did not match must not be reported");

		// The converse, on the same concept: filter on the OTHER designation and only it comes back.
		ValueSet.ValueSetExpansionContainsComponent sameConcept = expand("holidays").get(0);
		assertEquals("H101", sameConcept.getCode());
		assertEquals(1, sameConcept.getDesignation().size());
		assertEquals("Travel Premium - Nights and Holidays", sameConcept.getDesignation().get(0).getValue());
	}

	@Test
	void testDisplayMatchDoesNotSproutADesignation() {
		// "arthrogram" is in J002's display and in no designation of any concept, so J002 must come
		// back exactly as it did before this patch. If a designation appears here, we are claiming a
		// match reason that is not true.
		List<ValueSet.ValueSetExpansionContainsComponent> contains = expand("arthrogram");
		assertEquals(1, contains.size());
		assertEquals("J002", contains.get(0).getCode());
		assertTrue(contains.get(0).getDesignation().isEmpty(),
				"a concept matched on its display must not report its unrelated designation");
	}

	@Test
	void testUnfilteredExpansionReportsNoDesignations() {
		// No filter means no match to report, so nothing changes for the plain enumeration case.
		List<ValueSet.ValueSetExpansionContainsComponent> contains = expand(null);
		assertEquals(3, contains.size());
		assertTrue(contains.stream().allMatch(c -> c.getDesignation().isEmpty()),
				"an unfiltered expansion must not start emitting designations");
	}

	@Test
	void testAWordInBothDisplayAndDesignationReportsTheDesignation() {
		// "unilateral" is in J002's display AND in its designation. Both are true statements about
		// the match, and the designation is the more specific one, so it is reported. This also
		// guards the concept against being duplicated by the two-clause OR in the filter.
		List<ValueSet.ValueSetExpansionContainsComponent> contains = expand("unilateral");
		assertEquals(1, contains.size());
		assertEquals("J002", contains.get(0).getCode());
		assertEquals(1, contains.get(0).getDesignation().size());
		assertEquals("Bronchogram - unilateral", contains.get(0).getDesignation().get(0).getValue());
	}

	@Test
	void testIncludeDesignationsStillReturnsTheWholeSet() {
		// includeDesignations is not this patch's gate and its meaning is unchanged: it asks for the
		// concept's full designation set, matched or not. If this ever narrows to the matched subset
		// we have silently redefined a standard $expand parameter.
		ValueSet.ValueSetExpansionContainsComponent h101 = expandIncludingDesignations("weekdays").get(0);
		assertEquals("H101", h101.getCode());
		assertEquals(2, h101.getDesignation().size(), "includeDesignations=true must still return both designations");

		// And on a display-only match, where this patch contributes nothing.
		ValueSet.ValueSetExpansionContainsComponent j002 = expandIncludingDesignations("arthrogram").get(0);
		assertEquals("J002", j002.getCode());
		assertEquals(1, j002.getDesignation().size(), "includeDesignations=true is unaffected by how the concept matched");
	}

	/**
	 * The second path: a CodeSystem supplied as a `tx-resource` is never written to Elasticsearch,
	 * so it is filtered in memory by buildInlineConceptIfIncluded and reported by its own
	 * predicate. Fixing the Elasticsearch path does nothing for it.
	 */
	@Test
	void testOverlayDesignationOnlyMatchCarriesTheMatchedText() {
		List<ValueSet.ValueSetExpansionContainsComponent> contains = expandOverlay("weekdays");
		assertEquals(1, contains.size(), "an overlay CodeSystem must also match on its designations");
		ValueSet.ValueSetExpansionContainsComponent h101 = contains.get(0);
		assertEquals("H101", h101.getCode());
		assertEquals(1, h101.getDesignation().size(), "the overlay expansion must report the designation that matched");
		assertEquals("Travel Premium - Weekdays Daytime (07:00-17:00)", h101.getDesignation().get(0).getValue());
		assertEquals("Travel Premium", h101.getDisplay(), "the overlay display must survive a designation match too");

		// The same negatives, against the in-memory path.
		List<ValueSet.ValueSetExpansionContainsComponent> displayMatch = expandOverlay("arthrogram");
		assertEquals(1, displayMatch.size());
		assertTrue(displayMatch.get(0).getDesignation().isEmpty(),
				"an overlay concept matched on its display must not report its unrelated designation");
		assertTrue(expandOverlay(null).stream().allMatch(c -> c.getDesignation().isEmpty()),
				"an unfiltered overlay expansion must not start emitting designations");
	}

	@Test
	void testInlineAndIndexedFilterSemanticsDiffer() {
		// Why there are two predicates rather than one. buildInlineConceptIfIncluded filters on a
		// case-insensitive substring, so "days" matches inside "Weekdays"; the Elasticsearch query
		// filters on analyzed token prefixes, so "days" matches nothing. A single shared reporter
		// would therefore either invent a match reason on the Elasticsearch path or stay silent on
		// the inline one. If this test ever goes red, the two rules have converged or one of the
		// filters has changed, and the predicates have to be revisited together.
		assertEquals(0, expand("days").size(), "the indexed filter matches token prefixes, not infixes");

		List<ValueSet.ValueSetExpansionContainsComponent> overlay = expandOverlay("days");
		assertEquals(1, overlay.size(), "the in-memory filter matches substrings, so it finds 'days' inside 'Weekdays'");
		assertEquals("H101", overlay.get(0).getCode());
		// Both of H101's designations end in "days" (Weekdays, Holidays), so both matched and both
		// must be reported. "Report the matched designations" means all of them, not the first one
		// the loop happened to see.
		assertEquals(2, overlay.get(0).getDesignation().size(), "every designation that matched has to be reported");
		assertTrue(overlay.get(0).getDesignation().stream()
						.anyMatch(d -> "Travel Premium - Weekdays Daytime (07:00-17:00)".equals(d.getValue())));
		assertTrue(overlay.get(0).getDesignation().stream()
						.anyMatch(d -> "Travel Premium - Nights and Holidays".equals(d.getValue())));
	}

	private List<ValueSet.ValueSetExpansionContainsComponent> expand(String filter) {
		return expand(filter, false);
	}

	private List<ValueSet.ValueSetExpansionContainsComponent> expandIncludingDesignations(String filter) {
		return expand(filter, true);
	}

	private List<ValueSet.ValueSetExpansionContainsComponent> expand(String filter, boolean includeDesignations) {
		String url = baseUrl + "/ValueSet/$expand?url=" + IMPLICIT_VS
				+ (filter != null ? "&filter=" + filter : "")
				+ (includeDesignations ? "&includeDesignations=true" : "");
		return containsOf(restTemplate.exchange(url, HttpMethod.GET, null, String.class));
	}

	// POSTs the same CodeSystem as a tx-resource overlay under a URL that is NOT in Elasticsearch,
	// so the expansion has to be built in memory.
	private List<ValueSet.ValueSetExpansionContainsComponent> expandOverlay(String filter) {
		String filterParam = filter != null ? """
				,
						{
							"name": "filter",
							"valueString": "%s"
						}""".formatted(filter) : "";
		String body = """
				{
					"resourceType": "Parameters",
					"parameter": [
						{
							"name": "valueSet",
							"resource": {
								"resourceType": "ValueSet",
								"status": "draft",
								"compose": {
									"include": [
										{
											"system": "%s"
										}
									]
								}
							}
						},
						{
							"name": "tx-resource",
							"resource": %s
						}%s
					]
				}""".formatted(OVERLAY_CS_URL, CODE_SYSTEM_JSON.formatted(OVERLAY_CS_URL), filterParam);
		return containsOf(restTemplate.exchange(baseUrl + "/ValueSet/$expand", HttpMethod.POST, new HttpEntity<>(body, headers), String.class));
	}

	private List<ValueSet.ValueSetExpansionContainsComponent> containsOf(ResponseEntity<String> response) {
		assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
		assertNotNull(response.getBody());
		ValueSet valueSet = fhirJsonParser.parseResource(ValueSet.class, response.getBody());
		return valueSet.getExpansion().getContains();
	}
}
