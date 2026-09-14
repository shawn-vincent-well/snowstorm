package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A value set may combine SNOMED CT with another code system when it enumerates its codes.
 *
 * Expansion was refused whenever more than one code system version was involved and any of them
 * was SNOMED, on the grounds of pagination and totals. That reasoning holds for ECL, which can
 * match millions of concepts and so cannot be materialised -- but not for a compose that lists
 * its codes, where the result is bounded and known before any query runs.
 *
 * The refused shape is a common one rather than an exotic one: a handful of SNOMED concepts plus
 * a null-flavour or data-absent-reason code, which is how "asked, declined to answer" is modelled
 * in several national editions. Those value sets were loadable and not expandable.
 *
 * The ECL case is still refused, and the test below says so -- widening a restriction is easy to
 * overdo, and an implementation that simply deleted the check would pass every assertion here
 * except that one.
 */
class FHIRMixedExpansionTest extends AbstractFHIRTest {

	private static final String OTHER_CS = "http://example.com/fhir/CodeSystem/mixed-expansion-other";
	private static final String MIXED_VS = "http://example.com/fhir/ValueSet/mixed-enumerated";
	private static final String MIXED_ECL_VS = "http://example.com/fhir/ValueSet/mixed-with-ecl";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	@Autowired
	private FHIRValueSetService valueSetService;

	private FHIRCodeSystemVersion otherVersion;

	private static final String OTHER_CODE_SYSTEM = """
			{
				"resourceType": "CodeSystem", "url": "%s", "version": "1", "name": "MixedExpansionOther",
				"status": "draft", "content": "complete",
				"concept": [
					{ "code": "asked-declined", "display": "Asked but declined" },
					{ "code": "unknown", "display": "Unknown" }
				]
			}""";

	/** Two SNOMED concepts from the test data, plus two codes from a second system. */
	private static final String MIXED_VALUE_SET = """
			{
				"resourceType": "ValueSet", "url": "%s", "version": "1", "name": "MixedEnumerated",
				"status": "active",
				"compose": { "include": [
					{ "system": "http://snomed.info/sct", "concept": [ { "code": "%s" } ] },
					{ "system": "%s", "concept": [ { "code": "asked-declined" }, { "code": "unknown" } ] }
				] }
			}""";

	/** The same mixture, but the SNOMED half is an ECL filter rather than a code list. */
	private static final String MIXED_ECL_VALUE_SET = """
			{
				"resourceType": "ValueSet", "url": "%s", "version": "1", "name": "MixedWithEcl",
				"status": "active",
				"compose": { "include": [
					{ "system": "http://snomed.info/sct",
					  "filter": [ { "property": "concept", "op": "is-a", "value": "%s" } ] },
					{ "system": "%s", "concept": [ { "code": "unknown" } ] }
				] }
			}""";

	@BeforeEach
	void testSetup() throws ServiceException {
		CodeSystem cs = fhirJsonParser.parseResource(CodeSystem.class, OTHER_CODE_SYSTEM.formatted(OTHER_CS));
		otherVersion = codeSystemService.createUpdate(cs);
		conceptService.saveAllConceptsOfCodeSystemVersion(cs.getConcept(), otherVersion);

		valueSetService.createOrUpdateValuesetWithoutExpandValidation(fhirJsonParser.parseResource(
				ValueSet.class, MIXED_VALUE_SET.formatted(MIXED_VS, sampleSCTID, OTHER_CS)));
		valueSetService.createOrUpdateValuesetWithoutExpandValidation(fhirJsonParser.parseResource(
				ValueSet.class, MIXED_ECL_VALUE_SET.formatted(MIXED_ECL_VS, sampleSCTID, OTHER_CS)));
	}

	@AfterEach
	void testAfter() {
		// Only what this class stored: other FHIR tests share the repository.
		valueSetRepository.findAll().forEach(vs -> {
			if (MIXED_VS.equals(vs.getUrl()) || MIXED_ECL_VS.equals(vs.getUrl())) {
				valueSetRepository.deleteById(vs.getId());
			}
		});
		codeSystemService.deleteCodeSystemVersion(otherVersion);
	}

	private ResponseEntity<String> expand(String url) {
		return restTemplate.exchange(baseUrl + "/ValueSet/$expand?url=" + url, HttpMethod.GET, defaultRequestEntity, String.class);
	}

	@Test
	void testEnumeratedMixedValueSetExpands() {
		// Unpatched: HTTP 400, "does not yet support ValueSet$expand on ValueSets with multiple
		// code systems if any are SNOMED CT".
		ResponseEntity<String> response = expand(MIXED_VS);
		expectResponse(response, 200);
		ValueSet expanded = fhirJsonParser.parseResource(ValueSet.class, response.getBody());
		List<String> codes = expanded.getExpansion().getContains().stream()
				.map(ValueSet.ValueSetExpansionContainsComponent::getCode).sorted().toList();
		assertEquals(List.of("asked-declined", sampleSCTID, "unknown").stream().sorted().toList(), codes,
				"every enumerated code must be present, from both systems");
	}

	@Test
	void testTotalCountsBothSystems() {
		// The total is the whole point of the original restriction, so it is asserted directly
		// rather than inferred from the entries.
		ValueSet expanded = fhirJsonParser.parseResource(ValueSet.class, expand(MIXED_VS).getBody());
		assertEquals(3, expanded.getExpansion().getTotal(), "the total must span both code systems");
	}

	@Test
	void testEachConceptKeepsItsOwnSystem() {
		// A merged expansion is easy to get wrong by stamping one system on every entry.
		ValueSet expanded = fhirJsonParser.parseResource(ValueSet.class, expand(MIXED_VS).getBody());
		long snomed = expanded.getExpansion().getContains().stream()
				.filter(c -> "http://snomed.info/sct".equals(c.getSystem())).count();
		long other = expanded.getExpansion().getContains().stream()
				.filter(c -> OTHER_CS.equals(c.getSystem())).count();
		assertEquals(1, snomed, "the SNOMED concept must still say it is SNOMED");
		assertEquals(2, other, "the other system's concepts must still say so too");
	}

	@Test
	void testEclMixedValueSetIsStillRefused() {
		// THE GUARD ON THE GUARD. An ECL include can match millions of concepts, so the set
		// cannot be materialised and the cross-store pagination problem is real. Deleting the
		// restriction outright would pass every other test in this class.
		ResponseEntity<String> response = expand(MIXED_ECL_VS);
		assertEquals(400, response.getStatusCode().value(),
				"a mixed value set whose SNOMED half is ECL must still be refused");
		assertTrue(response.getBody() != null && response.getBody().contains("not-supported"),
				"and refused as not-supported, not as some incidental failure");
	}
}
