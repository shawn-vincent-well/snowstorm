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
 * A text `filter` on `$expand` must match a non-SNOMED concept's designations, not only its
 * display.
 *
 * The motivating case is a fee-schedule code system: 4,603 concepts carrying designations,
 * and 392 words that appear in a designation and in no display anywhere. A filter on any of
 * those words returned 0 results, even though $lookup on the matching concept returns the
 * designation containing exactly that word -- so every alternate name was invisible to
 * type-ahead while being perfectly visible to anyone who already knew the code.
 *
 * There are TWO independent filter paths and both are covered here:
 *   1. the Elasticsearch query in FHIRValueSetFinderService.getFhirConceptQuery
 *   2. the in-memory filter in FHIRValueSetService.buildInlineConceptIfIncluded, used for
 *      tx-resource overlay CodeSystems, which have no Elasticsearch documents. Fixing (1)
 *      does nothing for (2).
 *
 * The negative cases below are load-bearing. An OR over two fields is easy to get wrong in a
 * way that matches everything, and a test that only asserts "the designation hit comes back"
 * would pass just as happily against a query that had stopped filtering at all.
 */
class FHIRDesignationSearchTest extends AbstractFHIRTest {

	private static final String CS_URL = "http://example.com/fhir/CodeSystem/designation-search-test";
	private static final String IMPLICIT_VS = CS_URL + "?fhir_vs";
	private static final String OVERLAY_CS_URL = "http://example.com/fhir/CodeSystem/designation-search-overlay";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private FHIRCodeSystemVersion codeSystemVersion;

	// Three concepts modelled on real fee-schedule rows:
	//  - J002 carries the whole point: "bronchogram" lives only in a designation.
	//  - X001 gives a second designation-only word, to show one passing case is not a fluke.
	//  - A005 has no designation at all, and is the control the narrowing assertions use.
	//
	// The designations carry use = SNOMED 900000000000013009 (Synonym) with no language. That
	// shape is load-bearing for the display assertion below: selectDisplay ranks a use-less
	// language-tagged designation ABOVE the concept's own display, so a differently shaped
	// designation would legitimately become the display and the assertion would quietly be
	// testing something else.
	private static final String CODE_SYSTEM_JSON = """
			{
				"resourceType": "CodeSystem",
				"url": "%s",
				"version": "1",
				"name": "DesignationSearchTest",
				"status": "draft",
				"content": "complete",
				"concept": [
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
					},
					{
						"code": "X001",
						"display": "Diagnostic radiology of the spine",
						"designation": [
							{
								"use": {
									"system": "http://snomed.info/sct",
									"code": "900000000000013009",
									"display": "Synonym"
								},
								"value": "Discogram - lumbar"
							}
						]
					},
					{
						"code": "A005",
						"display": "Consultation"
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
	void testFilterMatchesAWordFoundOnlyInADesignation() {
		// "bronchogram" appears in no display in this code system. Before the patch: 0 results.
		List<ValueSet.ValueSetExpansionContainsComponent> contains = expand("bronchogram");
		assertEquals(1, contains.size(), "filter=bronchogram must find J002 through its designation");
		assertEquals("J002", contains.get(0).getCode());
		// Matched on the designation, but still labelled with the concept's display: search
		// broad, display preferred. The designation is not promoted into the display.
		assertEquals("Arthrogram, tenogram or bursogram unilateral", contains.get(0).getDisplay());

		contains = expand("discogram");
		assertEquals(1, contains.size(), "filter=discogram must find X001 through its designation");
		assertEquals("X001", contains.get(0).getCode());
	}

	@Test
	void testFilterMatchingNeitherDisplayNorDesignationReturnsNothing() {
		// The guard against an OR that matches everything. If this returns hits, the two-field
		// query has stopped filtering rather than widened.
		assertEquals(0, expand("zzzunmatchable").size(), "a word in no display and no designation must return nothing");
		assertEquals(0, expand("gastroscopy").size(), "a plausible clinical word that is absent must still return nothing");
	}

	@Test
	void testFilterStillNarrows() {
		// Unfiltered total, so the narrowing assertions below are against a known denominator.
		assertEquals(3, expand(null).size(), "all three concepts expand when no filter is given");

		// A display-only word must still work, and must not have picked up the other two.
		List<ValueSet.ValueSetExpansionContainsComponent> contains = expand("consultation");
		assertEquals(1, contains.size(), "filter=consultation must still narrow to the one display that contains it");
		assertEquals("A005", contains.get(0).getCode());

		// "unilateral" is in J002's display AND in J002's designation. Matching on both clauses
		// of the OR must still yield one concept, not a duplicated row.
		contains = expand("unilateral");
		assertEquals(1, contains.size(), "a word in both the display and the designation must not duplicate the concept");
		assertEquals("J002", contains.get(0).getCode());
	}

	/**
	 * The second blind path: a CodeSystem supplied as a `tx-resource` is never written to
	 * Elasticsearch, so buildInlineConceptIfIncluded filters it in memory instead. The
	 * Elasticsearch fix above does nothing for it.
	 */
	@Test
	void testInlineOverlayFilterMatchesADesignation() {
		List<ValueSet.ValueSetExpansionContainsComponent> contains = expandOverlay("bronchogram");
		assertEquals(1, contains.size(), "an overlay CodeSystem must also match on its designations");
		assertEquals("J002", contains.get(0).getCode());

		// The same three guards as above, against the in-memory path.
		assertEquals(0, expandOverlay("zzzunmatchable").size(), "the in-memory filter must still reject a word that is nowhere");
		assertEquals(3, expandOverlay(null).size(), "all three overlay concepts expand when no filter is given");
		contains = expandOverlay("consultation");
		assertEquals(1, contains.size(), "the in-memory filter must still narrow on a display-only word");
		assertEquals("A005", contains.get(0).getCode());
	}

	private List<ValueSet.ValueSetExpansionContainsComponent> expand(String filter) {
		String url = baseUrl + "/ValueSet/$expand?url=" + IMPLICIT_VS + (filter != null ? "&filter=" + filter : "");
		return containsOf(restTemplate.exchange(url, HttpMethod.GET, null, String.class));
	}

	// POSTs the same CodeSystem as a tx-resource overlay under a URL that is NOT in
	// Elasticsearch, so the expansion has to be built in memory.
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
