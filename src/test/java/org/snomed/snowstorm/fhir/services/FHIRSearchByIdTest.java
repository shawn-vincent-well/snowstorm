package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.fhir.domain.FHIRValueSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A search by _id must find the resource it names.
 *
 * SearchFilter qualified the supplied value with the resource type before comparing it -- so
 * "x" became "ValueSet/x" -- and compared that against Resource.getId(), which both ValueSet
 * and CodeSystem carry UNQUALIFIED. Every _id search therefore matched nothing and returned
 * an empty searchset with a 200 and no error, on a parameter the CapabilityStatement
 * advertises. Silence is the worst shape for this: a caller cannot tell "no such resource"
 * from "this parameter does not work".
 *
 * Both spellings are accepted deliberately. R4 allows a client to send either the bare id or
 * the type-qualified form, and IdType is used on both sides so neither is privileged.
 *
 * The negative case is load-bearing. Making the comparison succeed is easy to overdo -- a
 * filter that stopped narrowing at all would satisfy every positive assertion here.
 */
class FHIRSearchByIdTest extends AbstractFHIRTest {

	private static final String ID = "search-by-id-test-0001";
	private static final String OTHER_ID = "search-by-id-test-0002";
	private static final String URL = "http://search-by-id-test/vs-0001";
	private static final String OTHER_URL = "http://search-by-id-test/vs-0002";

	@Autowired
	private ElasticsearchOperations elasticsearchOperations;

	private final List<String> storedIds = new ArrayList<>();

	@BeforeEach
	void storeValueSets() {
		List<FHIRValueSet> toStore = List.of(valueSet(ID, URL), valueSet(OTHER_ID, OTHER_URL));
		toStore.forEach(vs -> storedIds.add(vs.getId()));
		valueSetRepository.saveAll(toStore);
		// Explicit rather than trusting the refresh interval: a test that races the index would
		// fail intermittently and read as a filter bug.
		elasticsearchOperations.indexOps(FHIRValueSet.class).refresh();
	}

	@AfterEach
	void removeValueSets() {
		// Only what this class stored. Other FHIR tests share the index.
		valueSetRepository.deleteAllById(storedIds);
		storedIds.clear();
		elasticsearchOperations.indexOps(FHIRValueSet.class).refresh();
	}

	private FHIRValueSet valueSet(String id, String url) {
		ValueSet hapi = new ValueSet();
		hapi.setId(id);
		hapi.setUrl(url);
		hapi.setVersion("1");
		hapi.setName("SearchByIdTest");
		hapi.setStatus(Enumerations.PublicationStatus.ACTIVE);
		return new FHIRValueSet(hapi);
	}

	private Bundle search(String query) {
		ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + "/ValueSet" + query, String.class);
		expectResponse(response, 200);
		return fhirJsonParser.parseResource(Bundle.class, response.getBody());
	}

	private List<String> urlsIn(Bundle bundle) {
		return bundle.getEntry().stream()
				.map(e -> ((ValueSet) e.getResource()).getUrl())
				.toList();
	}

	@Test
	void testSearchByBareIdFindsTheValueSet() {
		// Unpatched: empty. "search-by-id-test-0001" was compared as "ValueSet/search-by-id-test-0001".
		Bundle bundle = search("?_id=" + ID);
		assertEquals(List.of(URL), urlsIn(bundle), "_id names exactly one value set");
	}

	@Test
	void testSearchByQualifiedIdFindsTheSameValueSet() {
		Bundle bundle = search("?_id=ValueSet/" + ID);
		assertEquals(List.of(URL), urlsIn(bundle), "a caller may name the id in either form");
	}

	@Test
	void testSearchByIdThatMatchesNothingReturnsNothing() {
		// The control. A filter that had stopped narrowing would pass the two tests above.
		Bundle bundle = search("?_id=no-such-value-set");
		assertEquals(List.of(), urlsIn(bundle), "an id matching nothing must return nothing");
	}
}
