package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * _offset has to select a page on every listing endpoint, not just on ValueSet.
 *
 * CodeSystem and ConceptMap returned a List, which HAPI does page for _count, so _count looked
 * healthy on both. _offset did not work on either: HAPI delegates offsetting to a provider that
 * declares @Offset, and neither did, so HAPI handed the caller the entire result set while
 * advertising a page of the requested size in the next and previous links. Measured before patch
 * 10: GET /fhir/CodeSystem?_count=1&_offset=1 returned three entries for a page of one.
 *
 * These tests use whatever the shared test fixture happens to load rather than a corpus of their
 * own, so they assert page sizes and disjointness rather than particular resources.
 */
class FHIRSearchOffsetTest extends AbstractFHIRTest {

	private Bundle search(String type, String query) {
		ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + "/" + type + query, String.class);
		expectResponse(response, 200);
		return fhirJsonParser.parseResource(Bundle.class, response.getBody());
	}

	private List<String> idsIn(Bundle bundle) {
		return bundle.getEntry().stream()
				.map(e -> e.getResource().getIdElement().getIdPart())
				.toList();
	}

	private void assertOffsetSelectsAPage(String resourceType) {
		Bundle all = search(resourceType, "");
		assertTrue(all.getTotal() >= 2,
				() -> "This test needs at least two " + resourceType + " resources, the fixture has " + all.getTotal());

		Bundle first = search(resourceType, "?_count=1&_offset=0");
		Bundle second = search(resourceType, "?_count=1&_offset=1");

		assertEquals(1, first.getEntry().size(), () -> "_count=1 on " + resourceType + " means one entry");
		assertEquals(1, second.getEntry().size(), () -> "_count=1 with an offset on " + resourceType + " means one entry");
		assertNotEquals(idsIn(first).get(0), idsIn(second).get(0), "_offset has to move the window");
		assertEquals(all.getTotal(), first.getTotal(), "The total counts the whole result set, not the page");
	}

	@Test
	void testCodeSystemSearchHonoursOffset() {
		assertOffsetSelectsAPage("CodeSystem");
	}

	@Test
	void testConceptMapSearchHonoursOffset() {
		assertOffsetSelectsAPage("ConceptMap");
	}

	@Test
	void testOffsetPastTheEndIsAnEmptyPageRatherThanEverything() {
		Bundle all = search("ConceptMap", "");
		Bundle pastEnd = search("ConceptMap", "?_count=5&_offset=" + (all.getTotal() + 10));

		// The failure this guards is specific: an ignored offset does not return too few, it
		// returns everything, which is the answer a caller is least able to detect as wrong.
		assertEquals(0, pastEnd.getEntry().size(), "An offset past the end has no entries to show");
		assertEquals(all.getTotal(), pastEnd.getTotal(), "The total still describes the whole result set");
	}
}
