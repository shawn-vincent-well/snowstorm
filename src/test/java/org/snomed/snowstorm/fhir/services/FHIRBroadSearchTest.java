package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.domain.Concepts;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which concepts a text filter MATCHES must not be narrowed by the requested display language.
 *
 * The expansion scoped its description search to the dialects asked for as the DISPLAY
 * language, which conflates two different questions: "which descriptions may match my search
 * text" and "which term should be shown back to me".
 *
 * The consequence is that a description in a language nobody explicitly asked for is not
 * reachable by search at all. Because the dialect list always has the default English
 * dialects appended to it, English content stays findable whatever is requested -- which is
 * why this goes unnoticed on an English-only server. Content in any other language does not
 * get that protection: it is searchable only by a caller who already thought to ask for that
 * exact language, which is precisely the caller who least needs the help.
 *
 * The test data carries one Swedish description for this reason. Asking for Danish is a
 * well-formed request from a caller who is not thinking about Swedish, and it is enough to
 * make the Swedish description invisible.
 *
 * Display language is still honoured, just afterwards, when matched concepts are labelled.
 * That is a separate concern: these assertions are about WHICH concepts come back, and
 * deliberately not about what they are called.
 */
class FHIRBroadSearchTest extends AbstractFHIRTest {

	private static final String ALL_CONCEPTS = "http://snomed.info/sct?fhir_vs=ecl/<<" + Concepts.SNOMEDCT_ROOT;
	private static final String SWEDISH_ONLY_TERM = "Bakad";

	private List<String> expand(String extraParams) {
		String url = baseUrl + "/ValueSet/$expand?url=" + ALL_CONCEPTS + extraParams + "&_format=json";
		ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, defaultRequestEntity, String.class);
		expectResponse(response, 200);
		ValueSet valueSet = fhirJsonParser.parseResource(ValueSet.class, response.getBody());
		return valueSet.getExpansion().getContains().stream()
				.map(ValueSet.ValueSetExpansionContainsComponent::getCode)
				.toList();
	}

	@Test
	void testSwedishDescriptionIsFoundWhenSwedishIsAskedFor() {
		// Sanity, and true with or without the change: a caller who names the language the
		// content is in has always been able to find it.
		assertFalse(expand("&filter=" + SWEDISH_ONLY_TERM + "&displayLanguage=sv").isEmpty(),
				"fixture sanity: the Swedish description must be searchable when sv is requested");
	}

	@Test
	void testSwedishDescriptionIsFoundWhenADifferentLanguageIsAskedFor() {
		// The defect. Unpatched the search is scoped to {da, en}, so the Swedish description is
		// never examined and a caller asking in Danish is told the concept does not exist.
		assertEquals(expand("&filter=" + SWEDISH_ONLY_TERM + "&displayLanguage=sv"),
				expand("&filter=" + SWEDISH_ONLY_TERM + "&displayLanguage=da"),
				"which concepts match must not depend on the display language requested");
	}

	@Test
	void testEnglishContentIsUnaffected() {
		// Control. English is always in the dialect list, so this passes either way -- it is
		// here to show the change did not disturb the path that already worked.
		assertEquals(expand("&filter=potato"), expand("&filter=potato&displayLanguage=da"));
	}

	@Test
	void testFilterStillNarrows() {
		// The control that matters. Removing a language restriction from a query is easy to
		// overdo into removing the restriction altogether, and a test asserting only that
		// "results came back" would pass against a filter that had stopped filtering.
		assertEquals(List.of(), expand("&filter=zzzznotarealword&displayLanguage=da"),
				"a filter matching nothing must still return nothing");
	}
}
