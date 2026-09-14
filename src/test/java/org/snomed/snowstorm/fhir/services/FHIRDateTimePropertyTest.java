package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A concept property declared as dateTime must survive storage and come back from $lookup.
 *
 * FHIRProperty handles boolean, integer, decimal, code and string, but had no branch for
 * dateTime: the value was stored null and the property vanished from $lookup entirely rather
 * than being returned in the wrong form. Date-valued properties are common in a released
 * terminology -- effectiveDate and retirementDate being the usual pair -- so the gap is not
 * an edge case.
 *
 * The string-property assertion is not filler. The fix adds a branch to a type dispatch, and
 * the way to get that wrong is to shadow an existing branch; a test that only proved dateTime
 * works would pass just as happily against a change that had broken every other type.
 */
class FHIRDateTimePropertyTest extends AbstractFHIRTest {

	private static final String CS_URL = "http://example.com/fhir/CodeSystem/datetime-property-test";
	private static final String EFFECTIVE_DATE = "2024-03-01T00:00:00+00:00";

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
				"name": "DateTimePropertyTest",
				"status": "draft",
				"content": "complete",
				"property": [
					{ "code": "effectiveDate", "type": "dateTime" },
					{ "code": "sourceName", "type": "string" }
				],
				"concept": [
					{
						"code": "A1",
						"display": "Concept carrying a dateTime property",
						"property": [
							{ "code": "effectiveDate", "valueDateTime": "%s" },
							{ "code": "sourceName", "valueString": "release-2024" }
						]
					},
					{
						"code": "A2",
						"display": "Concept carrying no properties at all"
					}
				]
			}""";

	@BeforeEach
	void testSetup() throws ServiceException {
		CodeSystem codeSystem = fhirJsonParser.parseResource(CodeSystem.class,
				CODE_SYSTEM_JSON.formatted(CS_URL, EFFECTIVE_DATE));
		codeSystemVersion = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), codeSystemVersion);
	}

	@AfterEach
	void testAfter() {
		codeSystemService.deleteCodeSystemVersion(codeSystemVersion);
	}

	private Parameters lookup(String code) {
		return getParameters(baseUrl + "/CodeSystem/$lookup?system=" + CS_URL + "&code=" + code);
	}

	@Test
	void testDateTimePropertyIsReturnedByLookup() {
		Type value = getProperty(lookup("A1"), "effectiveDate");
		assertNotNull(value, "a dateTime property must be returned by $lookup, not dropped");
		assertInstanceOf(DateTimeType.class, value, "a dateTime property must come back as a dateTime");
		assertEquals(EFFECTIVE_DATE, ((DateTimeType) value).getValueAsString());
	}

	@Test
	void testStringPropertyOnTheSameConceptStillWorks() {
		// The control: adding a branch to a type dispatch must not shadow the existing ones.
		Type value = getProperty(lookup("A1"), "sourceName");
		assertNotNull(value, "the string property must be unaffected by the dateTime branch");
		assertInstanceOf(StringType.class, value);
		assertEquals("release-2024", ((StringType) value).getValue());
	}

	@Test
	void testConceptWithNoPropertiesReportsNone() {
		// Guards the other direction: a dispatch that returns a value for everything would
		// pass both assertions above.
		assertNull(getProperty(lookup("A2"), "effectiveDate"),
				"a concept with no properties must not acquire one");
	}
}
