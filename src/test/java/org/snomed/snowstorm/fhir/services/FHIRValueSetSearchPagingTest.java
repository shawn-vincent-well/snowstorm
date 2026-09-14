package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.fhir.domain.FHIRValueSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GET /fhir/ValueSet has to be enumerable, and has to honour the search parameters it declares.
 *
 * It was neither. The plain listing was built from PageRequest.of(0, 1_000) with no
 * next link, so a server holding 3,464 value sets showed 1,000 of them and gave a client no way to
 * tell; _count and _offset were accepted and ignored; and the reported total was taken before the
 * in-memory filter ran, so a search that matched nothing still announced a total of everything.
 *
 * These are all the same failure: a bundle that looks like a complete answer and is not.
 */
class FHIRValueSetSearchPagingTest extends AbstractFHIRTest {

	/** Deliberately above the 1,000 the unpatched listing was hardcoded to. */
	private static final int STORED_VALUE_SETS = 1_005;

	private static final String URL_PREFIX = "http://paging-test/vs-";
	private static final String ID_PREFIX = "paging-test-";
	/**
	 * Two versions of one url, so a version filter has something to choose between. Named to sort
	 * after the numbered urls, because the listing is ordered by url and these tests assert which
	 * value sets a given page holds.
	 */
	private static final String TWO_VERSION_URL = "http://paging-test/zz-two-versions";

	@Autowired
	private ElasticsearchOperations elasticsearchOperations;

	private final List<String> storedIds = new ArrayList<>();

	@BeforeEach
	void storeValueSets() {
		List<FHIRValueSet> toStore = new ArrayList<>();
		for (int i = 0; i < STORED_VALUE_SETS; i++) {
			// Zero padded so that the url sort order and the numeric order agree, which lets a test
			// assert which value sets a given page should hold.
			toStore.add(valueSet(format("%s%04d", ID_PREFIX, i), format("%s%04d", URL_PREFIX, i), "1"));
		}
		toStore.add(valueSet(ID_PREFIX + "two-versions-1", TWO_VERSION_URL, "1"));
		toStore.add(valueSet(ID_PREFIX + "two-versions-2", TWO_VERSION_URL, "2"));

		toStore.forEach(vs -> storedIds.add(vs.getId()));
		valueSetRepository.saveAll(toStore);
		// Explicit rather than trusting the index refresh interval. A test that races the index
		// would fail intermittently and be read as a paging bug, which is the opposite of useful.
		elasticsearchOperations.indexOps(FHIRValueSet.class).refresh();
	}

	@AfterEach
	void removeValueSets() {
		// Only what this class stored. Other FHIR tests share the index.
		valueSetRepository.deleteAllById(storedIds);
		storedIds.clear();
		elasticsearchOperations.indexOps(FHIRValueSet.class).refresh();
	}

	private FHIRValueSet valueSet(String id, String url, String version) {
		ValueSet hapi = new ValueSet();
		hapi.setId(id);
		hapi.setUrl(url);
		hapi.setVersion(version);
		hapi.setName("PagingTest");
		hapi.setStatus(org.hl7.fhir.r4.model.Enumerations.PublicationStatus.ACTIVE);
		return new FHIRValueSet(hapi);
	}

	private Bundle search(String query) {
		ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + "/ValueSet" + query, String.class);
		expectResponse(response, 200);
		return fhirJsonParser.parseResource(Bundle.class, response.getBody());
	}

	private Optional<String> link(Bundle bundle, String relation) {
		return bundle.getLink().stream()
				.filter(l -> relation.equals(l.getRelation()))
				.map(Bundle.BundleLinkComponent::getUrl)
				.findFirst();
	}

	private List<String> idsIn(Bundle bundle) {
		return bundle.getEntry().stream()
				.map(e -> e.getResource().getIdElement().getIdPart())
				.toList();
	}

	private List<String> urlsIn(Bundle bundle) {
		return bundle.getEntry().stream()
				.map(e -> ((ValueSet) e.getResource()).getUrl())
				.toList();
	}

	@Test
	void testCountIsHonoured() {
		Bundle bundle = search("?_count=5");

		assertEquals(5, bundle.getEntry().size(), "_count asked for 5 value sets");
		// The total is the size of the whole result set, not of the page, which is what makes the
		// next link meaningful.
		assertEquals(STORED_VALUE_SETS + 2, bundle.getTotal());
		assertTrue(link(bundle, "next").isPresent(), "A page smaller than the result set needs a next link");
	}

	@Test
	void testOffsetSelectsALaterPage() {
		List<String> firstPage = urlsIn(search("?_count=3&_offset=0"));
		List<String> secondPage = urlsIn(search("?_count=3&_offset=3"));

		assertEquals(3, firstPage.size());
		assertEquals(3, secondPage.size());
		assertEquals(List.of(URL_PREFIX + "0000", URL_PREFIX + "0001", URL_PREFIX + "0002"), firstPage);
		assertEquals(List.of(URL_PREFIX + "0003", URL_PREFIX + "0004", URL_PREFIX + "0005"), secondPage);
	}

	@Test
	void testEveryStoredValueSetIsReachableByFollowingNext() {
		// The point of the patch: walk the whole listing the way a client would, and land on every
		// value set exactly once. Unpatched this stops at 1,000 with no next link to follow.
		// Keyed on id rather than url, because the two-version pair shares a url.
		Set<String> seen = new LinkedHashSet<>();
		Bundle page = search("?_count=250");
		int total = page.getTotal();
		int pagesFollowed = 0;

		while (true) {
			for (String id : idsIn(page)) {
				assertTrue(seen.add(id), () -> "Value set " + id + " was returned on two different pages");
			}
			Optional<String> next = link(page, "next");
			if (next.isEmpty()) {
				break;
			}
			assertTrue(++pagesFollowed < 50, "Following next links did not terminate");
			ResponseEntity<String> response = restTemplate.getForEntity(next.get(), String.class);
			expectResponse(response, 200);
			page = fhirJsonParser.parseResource(Bundle.class, response.getBody());
		}

		assertEquals(STORED_VALUE_SETS + 2, total, "Reported total");
		assertEquals(STORED_VALUE_SETS + 2, seen.size(), "Value sets actually reachable by paging");
		assertTrue(seen.contains(ID_PREFIX + "1004"), "The last value set is past the old hardcoded 1,000");
	}

	@Test
	void testPlainSearchReportsTheHonestTotal() {
		Bundle bundle = search("");

		assertEquals(STORED_VALUE_SETS + 2, bundle.getTotal());
		assertEquals(bundle.getTotal(), bundle.getEntry().size(),
				"With no _count the whole result set is returned, so total and entry count agree");
	}

	@Test
	void testVersionFiltersAUrlSearch() {
		Bundle both = search("?url=" + TWO_VERSION_URL);
		assertEquals(2, both.getEntry().size());
		assertEquals(2, both.getTotal());

		Bundle one = search("?url=" + TWO_VERSION_URL + "&version=1");
		assertEquals(1, one.getEntry().size(), "version=1 selects one of the two");
		// This is the assertion the unpatched code fails: it reported the count of everything at
		// that url, taken before the version filter ran.
		assertEquals(1, one.getTotal(), "The total must count what matched, not what was looked at");
		assertEquals("1", ((ValueSet) one.getEntry().get(0).getResource()).getVersion());
	}

	@Test
	void testBogusVersionOnAUrlSearchMatchesNothing() {
		Bundle bundle = search("?url=" + TWO_VERSION_URL + "&version=BOGUS");

		assertEquals(0, bundle.getEntry().size());
		// Unpatched this says 2 over an empty entry list -- a client reading total concludes the
		// version exists.
		assertEquals(0, bundle.getTotal(), "A version that matches nothing must total nothing");
	}

	@Test
	void testBogusVersionWithoutAUrlMatchesNothing() {
		Bundle bundle = search("?version=BOGUS");

		assertEquals(0, bundle.getEntry().size());
		// Unpatched this reports the size of the entire store.
		assertEquals(0, bundle.getTotal(), "A version that matches nothing must total nothing");
	}

}
