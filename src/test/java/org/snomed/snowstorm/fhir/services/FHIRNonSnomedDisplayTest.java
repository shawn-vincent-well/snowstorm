package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A non-SNOMED concept's display must honour the requested language.
 *
 * The SNOMED path picks a term for the requested dialect through
 * FHIRHelper.getPreferredTerm. Nothing equivalent ran for a non-SNOMED concept: the display
 * was read straight off the concept, so language-tagged designations were stored, indexed
 * and returned by $lookup while having no influence on which string came back AS the
 * display. A concept carrying designations and no display of its own answered blank.
 *
 * The region-subtag case is the sharp edge and is why the raw requested tag is consulted
 * alongside the parsed dialects. LanguageDialect reduces "fr-CA" to language code "fr" plus a
 * reference set id, so the region is not recoverable from the parsed list alone -- a request
 * for fr-CA would silently be answered with generic fr. A test asserting only that "some
 * French came back" would pass against exactly that bug.
 */
class FHIRNonSnomedDisplayTest extends AbstractFHIRTest {

	private static final String CS_URL = "http://example.com/fhir/CodeSystem/non-snomed-display-test";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private FHIRCodeSystemVersion codeSystemVersion;

	private static final String CODE_SYSTEM_JSON = """
			{
				"resourceType": "CodeSystem",
				"url": "%s",
				"version": "1",
				"name": "NonSnomedDisplayTest",
				"status": "draft",
				"content": "complete",
				"concept": [
					{
						"code": "NO-DISPLAY",
						"designation": [
							{ "language": "fr-CA", "value": "Bonjour du Canada" },
							{ "language": "fr", "value": "Bonjour" },
							{ "language": "en", "value": "Hello" }
						]
					},
					{
						"code": "BOTH",
						"display": "Consultation",
						"designation": [
							{ "language": "fr", "value": "Consultation en francais" }
						]
					},
					{
						"code": "DISPLAY-ONLY",
						"display": "Plain display, no designations"
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

	private String display(String code, String languageSuffix) {
		Parameters parameters = getParameters(baseUrl + "/CodeSystem/$lookup?system=" + CS_URL
				+ "&code=" + code + languageSuffix);
		Type display = getProperty(parameters, "display");
		return display == null ? null : display.primitiveValue();
	}

	@Test
	void testConceptWithNoDisplayOfItsOwnUsesADesignation() {
		// Unpatched: blank. The display was read off the concept, which has none.
		String display = display("NO-DISPLAY", "&displayLanguage=en");
		assertNotNull(display, "a concept carrying designations must not answer with no display");
		assertEquals("Hello", display);
	}

	@Test
	void testRegionSubtagIsNotFlattenedToTheBaseLanguage() {
		// The sharp edge: fr-CA must not silently be answered with generic fr.
		assertEquals("Bonjour du Canada", display("NO-DISPLAY", "&displayLanguage=fr-CA"),
				"the requested region subtag must win over the base language");
	}

	@Test
	void testMatchingDesignationIsPreferredOverTheConceptDisplay() {
		assertEquals("Consultation en francais", display("BOTH", "&displayLanguage=fr"));
	}

	@Test
	void testConceptDisplayStandsWhenNoDesignationMatches() {
		// Control. The fallback must not fire when the requested language matches nothing;
		// the concept's own display is still the right answer.
		assertEquals("Consultation", display("BOTH", ""));
	}

	@Test
	void testConceptWithNoDesignationsIsUnaffected() {
		// Control. Selecting among designations must not disturb a concept that has none.
		assertEquals("Plain display, no designations", display("DISPLAY-ONLY", "&displayLanguage=fr-CA"));
	}
}
