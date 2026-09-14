package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.*;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.domain.Identifier;
import org.snomed.snowstorm.core.data.domain.expression.Expression;
import org.snomed.snowstorm.core.data.services.CodeSystemDefaultConfigurationService;
import org.snomed.snowstorm.core.data.services.ConceptService;
import org.snomed.snowstorm.core.data.services.ExpressionService;
import org.snomed.snowstorm.core.data.services.pojo.CodeSystemDefaultConfiguration;
import org.snomed.snowstorm.core.pojo.LanguageDialect;
import org.snomed.snowstorm.fhir.config.FHIRConstants;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.snomed.snowstorm.fhir.domain.FHIRConcept;
import org.snomed.snowstorm.fhir.domain.FHIRDesignation;
import org.snomed.snowstorm.fhir.domain.FHIRProperty;
import org.snomed.snowstorm.fhir.pojo.ConceptAndSystemResult;
import org.springframework.stereotype.Service;

import java.util.*;

import static java.lang.String.format;
import static org.hl7.fhir.r4.model.CodeSystem.CodeSystemContentMode.FRAGMENT;
import static org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity.ERROR;
import static org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity.WARNING;
import static org.snomed.snowstorm.fhir.services.FHIRHelper.createOperationOutcomeIssueComponent;
import static org.snomed.snowstorm.fhir.services.FHIRHelper.createParameterComponentWithOperationOutcomeWithIssues;
import static org.snomed.snowstorm.fhir.services.FHIRValueSetService.TX_ISSUE_TYPE;

@Service
public class HapiParametersMapper implements FHIRConstants {

	// Needed by baseLanguage() below.
	private static final char LANGUAGE_SUBTAG_SEPARATOR = '-';

	private static final String PART_DESCRIPTION = "description";

	private final ExpressionService expressionService;

	private final FHIRHelper fhirHelper;

	private final CodeSystemDefaultConfigurationService codeSystemDefaultConfigurationService;

	private final ConceptService snomedConceptService;

	public HapiParametersMapper(ExpressionService expressionService, FHIRHelper fhirHelper, CodeSystemDefaultConfigurationService codeSystemDefaultConfigurationService, ConceptService snomedConceptService) {
		this.expressionService = expressionService;
		this.fhirHelper = fhirHelper;
		this.codeSystemDefaultConfigurationService = codeSystemDefaultConfigurationService;
		this.snomedConceptService = snomedConceptService;
	}

	public Parameters singleOutValue(String key, String value) {
		Parameters parameters = new Parameters();
		parameters.addParameter(key, value);
		return parameters;
	}

	public Parameters singleOutValue(String key, String value, FHIRCodeSystemVersion codeSystemVersion) {
		Parameters parameters = singleOutValue(key, value);
		addSystemAndVersion(parameters, codeSystemVersion);
		return parameters;
	}

	public Parameters resultFalse(String code, FHIRCodeSystemVersion codeSystemVersion) {
		Parameters parameters = new Parameters();
		parameters.addParameter(CODE, new CodeType(code));
		addSystemAndVersion(parameters, codeSystemVersion);

		String systemUrl = FHIRHelper.isSnomedUri(codeSystemVersion.getUrl()) ? SNOMED_URI : codeSystemVersion.getUrl();
		String message = format("Unknown code '%s' in the CodeSystem '%s' version '%s'", code, systemUrl, codeSystemVersion.getVersion());
		OperationOutcome.IssueSeverity severity;
		if(FRAGMENT.toCode().equals(codeSystemVersion.getContent())) {
			severity = WARNING;
			parameters.addParameter(RESULT, true);
			message = message + " - note that the code system is labeled as a fragment, so the code may be valid in some other fragment";
		} else {
			severity = ERROR;
			parameters.addParameter(RESULT, false);
		}
		parameters.addParameter(MESSAGE, message);
		OperationOutcome.OperationOutcomeIssueComponent[] issues = new OperationOutcome.OperationOutcomeIssueComponent[1];
		CodeableConcept detail1 = new CodeableConcept(new Coding(TX_ISSUE_TYPE, "invalid-code", null)).setText(message);
		List<Extension> extensions = Collections.singletonList(new Extension("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",new StringType("Unknown_Code_in_Version")));
		issues[0] = createOperationOutcomeIssueComponent(detail1, severity, null, OperationOutcome.IssueType.CODEINVALID, extensions, null);
		parameters.addParameter(createParameterComponentWithOperationOutcomeWithIssues(Arrays.asList(issues)));
		return parameters;
	}

	public Parameters mapToFHIR(ConceptAndSystemResult conceptAndSystemResult, Collection<String> childIds,
	                            Set<FhirSctProperty> properties, List<LanguageDialect> languageDialects) {

		FHIRCodeSystemVersion codeSystemVersion = conceptAndSystemResult.codeSystemVersion();
		Concept concept = conceptAndSystemResult.concept();

		Parameters parameters = new Parameters();
		parameters.addParameter(CODE, new CodeType(concept.getConceptId()));
		addDesignations(parameters, concept);
		parameters.addParameter(DISPLAY, fhirHelper.getPreferredTerm(concept, languageDialects));
		String nameSystemUrl = FHIRHelper.isSnomedUri(codeSystemVersion.getUrl()) ? SNOMED_URI : codeSystemVersion.getUrl();
		parameters.addParameter("name", nameSystemUrl + "|" + codeSystemVersion.getVersion());
		addProperties(parameters, concept, properties, conceptAndSystemResult, languageDialects);
		addParents(parameters, concept);
		addChildren(parameters, childIds, conceptAndSystemResult, languageDialects);
		addIdentifiers(parameters, concept);
		addSystemAndVersion(parameters, codeSystemVersion);
		return parameters;
	}

	private void addSystemAndVersion(Parameters parameters, FHIRCodeSystemVersion codeSystem) {
		String systemUrl = FHIRHelper.isSnomedUri(codeSystem.getUrl()) ? SNOMED_URI : codeSystem.getUrl();
		parameters.addParameter(SYSTEM, new UriType(systemUrl));
		parameters.addParameter(VERSION, codeSystem.getVersion());
	}

	/**
	 * Select the display term for a non-SNOMED concept, honouring the requested
	 * language(s) by matching against the concept designations, and falling back to
	 * the concept's own display when nothing matches. Mirrors what the SNOMED path
	 * does via FHIRHelper.getPreferredTerm, which is not reachable for non-SNOMED
	 * concepts.
	 *
	 * Candidates are assembled in order: the raw requested tag(s) first, then each
	 * parsed dialect. Both are needed. LanguageDialect reduces "fr-CA" to
	 * languageCode "fr" plus a SNOMED language reference set id, so the BCP-47
	 * region subtag is not recoverable from the parsed list -- without the raw tag,
	 * a request for fr-CA silently returns generic fr.
	 *
	 * The nested loop below is load-bearing; see the comment above it.
	 */
	public static String selectDisplay(FHIRConcept concept, List<LanguageDialect> dialects, String requestedLanguage) {
		if (concept.getDesignations() == null || concept.getDesignations().isEmpty()) {
			return concept.getDisplay();
		}
		List<String> candidates = new ArrayList<>();
		// requestedLanguage may be a raw header value: "fr-CA;q=0.8, en;q=0.5".
		// Split it and drop the weights so the exact-subtag match can fire.
		if (requestedLanguage != null && !requestedLanguage.isBlank()) {
			for (String part : requestedLanguage.split(",")) {
				String tag = part.split(";")[0].trim();
				if (!tag.isEmpty() && !tag.equals("*")) {
					candidates.add(tag);
				}
			}
		}
		if (dialects != null) {
			for (LanguageDialect dialect : dialects) {
				if (dialect.getLanguageCode() != null) {
					candidates.add(dialect.getLanguageCode());
				}
			}
		}
		// Candidate order is load-bearing. Each candidate is tried exact THEN base
		// before moving to the next one. It must NOT be reorganised into "all
		// candidates exact, then all candidates base": parseAcceptLanguageHeader
		// appends an English fallback chain, so the candidates for "fr" are
		// [fr, fr, en, en, en], and an all-exact-first pass lets exact "en" beat the
		// "fr" -> "fr-CA" base match that was actually requested. That regression has
		// been introduced once already; it is covered by
		// FHIRDisplayLanguageTest.testLookupBaseLanguageFallsBackToRegionalDesignation.
		for (String candidate : candidates) {
			for (boolean exact : new boolean[] { true, false }) {
				String match = matchDesignation(concept, candidate, exact);
				if (match != null) {
					return match;
				}
			}
		}
		if (concept.getDisplay() != null && !concept.getDisplay().isBlank()) {
			return concept.getDisplay();
		}
		// No base display to fall back to, so a non-display designation beats nothing.
		for (String candidate : candidates) {
			for (boolean exact : new boolean[] { true, false }) {
				String match = matchDesignation(concept, candidate, exact, 2);
				if (match != null) {
					return match;
				}
			}
		}
		return concept.getDisplay();
	}

	/**
	 * Best designation in the wanted language, RANKED BY `use`.
	 *
	 * `designation.use` says what kind of representation this is, and only some kinds
	 * are display terms. Ignoring it let a synonym hijack the display: a concept whose
	 * display was "Hip replacement" with an en designation "THR"
	 * (use=900000000000013009, synonym) answered $lookup with "THR". Worse is
	 * available -- SNOMED 900000000000550004 is a DEFINITION, and v3-ActCode AMB
	 * carries one that is a full paragraph of prose. Rendering that as a display would
	 * be actively harmful in a picker.
	 *
	 * This matters more, not less, as the terminology server grows: carrying synonyms
	 * as designations is precisely how it is meant to feed AI training and search, so
	 * the moment anyone loads them the displays start degrading.
	 *
	 * Ranking, best first:
	 *   0  explicitly the display for that language (hl7TermMaintInfra
	 *      `preferredForLanguage`, or the HL7 designation-usage `display`). Infoway's
	 *      French supplements all use the first of these, so this is the common case.
	 *   1  no `use` at all -- a plain alternative in that language.
	 *   2  anything else. NOT a display term, and only reachable when the concept has
	 *      no base display to fall back to (v3-ActCode concepts loaded from a merged
	 *      supplement have exactly that shape).
	 */
	private static final String PREFERRED_FOR_LANGUAGE = "preferredForLanguage";
	private static final String DISPLAY_USE = "display";

	private static String matchDesignation(FHIRConcept concept, String wanted, boolean exact) {
		return matchDesignation(concept, wanted, exact, 1);
	}

	private static String matchDesignation(FHIRConcept concept, String wanted, boolean exact, int worstRankAllowed) {
		String want = exact ? wanted : baseLanguage(wanted);
		String best = null;
		int bestRank = Integer.MAX_VALUE;
		for (FHIRDesignation designation : concept.getDesignations()) {
			String lang = designation.getLanguage();
			if (lang == null || designation.getValue() == null) {
				continue;
			}
			String have = exact ? lang : baseLanguage(lang);
			if (!have.equalsIgnoreCase(want)) {
				continue;
			}
			int rank = rankOf(designation);
			if (rank <= worstRankAllowed && rank < bestRank) {
				bestRank = rank;
				best = designation.getValue();
			}
		}
		return best;
	}

	private static int rankOf(FHIRDesignation designation) {
		if (!hasUse(designation)) {
			return 1;
		}
		String use = designation.getUse();
		int pipe = use.lastIndexOf('|');
		String code = pipe >= 0 ? use.substring(pipe + 1) : use;
		return PREFERRED_FOR_LANGUAGE.equals(code) || DISPLAY_USE.equals(code) ? 0 : 2;
	}

	/**
	 * Whether a designation actually carries a `use`.
	 *
	 * Cannot be a null check. FHIRDesignation.setUse concatenates unconditionally --
	 * `use = useSystem + "|" + useCode` -- so a designation with NO use is stored as
	 * the literal string "null|null" rather than null. getUseCoding() then hands back
	 * a Coding of system "null", code "null". Treat that as absent.
	 */
	private static boolean hasUse(FHIRDesignation designation) {
		String use = designation.getUse();
		return use != null && !use.isBlank() && !"null|null".equals(use) && !use.startsWith("null|null");
	}

	/** The primary language subtag: "fr-CA" -> "fr". */
	private static String baseLanguage(String lang) {
		int dash = lang.indexOf(LANGUAGE_SUBTAG_SEPARATOR);
		return dash > 0 ? lang.substring(0, dash) : lang;
	}

	// The no-language overload, kept for callers that have no language in scope. The
	// language-carrying overload below is the one a $lookup should use: without it the
	// display is read straight off the concept, which is blank whenever the concept carries
	// designations but no base display of its own.
	public Parameters mapToFHIR(FHIRCodeSystemVersion codeSystemVersion, FHIRConcept concept) {
		return mapToFHIR(codeSystemVersion, concept, null, null);
	}

	public Parameters mapToFHIR(FHIRCodeSystemVersion codeSystemVersion, FHIRConcept concept,
			List<LanguageDialect> designations, String requestedLanguage) {
		Parameters parameters = new Parameters();
		Optional.of(codeSystemVersion.getName()).ifPresent(x->parameters.addParameter("name", x));
		parameters.addParameter(SYSTEM, new UriType(codeSystemVersion.getUrl()));
		parameters.addParameter(VERSION, codeSystemVersion.getVersion());
		// Never a blank display; see selectDisplay.
		parameters.addParameter(DISPLAY, selectDisplay(concept, designations, requestedLanguage));
		parameters.addParameter(CODE, new CodeType(concept.getCode()));

		addConceptProperties(parameters, codeSystemVersion, concept);

		addConceptDesignations(parameters, concept);

		return parameters;
	}

	private void addConceptProperties(Parameters parameters, FHIRCodeSystemVersion codeSystemVersion, FHIRConcept concept) {
		for (Map.Entry<String, List<FHIRProperty>> property : concept.getProperties().entrySet()) {
			for (FHIRProperty propertyValue : property.getValue()) {
				String code = propertyValue.getCode();
				Type hapiValue = propertyValue.toHapiValue(codeSystemVersion.getUrl());
				if (DEFINITION.equals(code) && hapiValue instanceof StringType hapiString) {
					parameters.addParameter(DEFINITION, hapiString.getValue());
				} else if (NOT_SELECTABLE.equals(code) && hapiValue instanceof BooleanType hapiBool) {
					parameters.addParameter(ABSTRACT, hapiBool.getValue());
					Parameters.ParametersParameterComponent param = parameters.addParameter().setName(PROPERTY);
					param.addPart().setName(CODE).setValue(new CodeType(code));
					param.addPart().setName(VALUE).setValue(hapiValue);
				} else {
					Parameters.ParametersParameterComponent param = parameters.addParameter().setName(PROPERTY);
					param.addPart().setName(CODE).setValue(new CodeType(code));
					param.addPart().setName(VALUE).setValue(hapiValue);
				}
			}
		}
	}

	private void addConceptDesignations(Parameters parameters, FHIRConcept concept) {
		for (FHIRDesignation designation : concept.getDesignations()) {
			Parameters.ParametersParameterComponent desParam = parameters.addParameter().setName(DESIGNATION);
			if (designation.getLanguage() != null) {
				desParam.addPart().setName(LANGUAGE).setValue(new CodeType(designation.getLanguage()));
			}
			String use = designation.getUse();
			if (use != null && !use.contains("null")) {
				Type type;
				if (use.contains("|")) {
					String[] parts = use.split("\\|", 2);
					type = new Coding(parts[0], parts[1], parts[1]);
				} else {
					type = new CodeType(use);
				}
				desParam.addPart().setName(USE).setValue(type);
			}
			if (designation.getValue() != null) {
				desParam.addPart().setName(VALUE).setValue(new StringType(designation.getValue()));
			}
		}
	}

	public Parameters validateCodeResponse(FHIRConcept concept, boolean displayValidOrNull, FHIRCodeSystemVersion codeSystemVersion) {
		Parameters parameters = new Parameters();
		parameters.addParameter(RESULT, displayValidOrNull);
		parameters.addParameter(CODE, new CodeType(concept.getCode()));
		parameters.addParameter(SYSTEM, new UriType(codeSystemVersion.getUrl()));
		if (!"0".equals(codeSystemVersion.getVersion())) {
			parameters.addParameter(VERSION, codeSystemVersion.getVersion());
		}
		if (!displayValidOrNull) {
			parameters.addParameter(MESSAGE, "The code exists but the display is not valid.");
		}
		parameters.addParameter(DISPLAY, concept.getDisplay());
		return parameters;
	}

	private void addDesignations(Parameters parameters, Concept c) {
		for (Description d : c.getActiveDescriptions()) {
			Parameters.ParametersParameterComponent designation = parameters.addParameter().setName(DESIGNATION);
			designation.addPart().setName(LANGUAGE).setValue(new CodeType(d.getLang()));
			designation.addPart().setName(USE).setValue(new Coding(SNOMED_URI, d.getTypeId(), FHIRHelper.translateDescType(d.getTypeId())));
			designation.addPart().setName(VALUE).setValue(new StringType(d.getTerm()));
		}
	}

	private void addIdentifiers(Parameters parameters, Concept c) {
		for (Identifier identifier: c.getIdentifiers()) {
			CodeSystemDefaultConfiguration codeSystem = codeSystemDefaultConfigurationService.findByAlternativeSchemaSctid(identifier.getIdentifierSchemaId());
			String alternateSchemaUri = codeSystem != null ? codeSystem.alternateSchemaUri() : null;
			Coding coding = new Coding(alternateSchemaUri, identifier.getAlternateIdentifier(), null);
			parameters.addParameter(createProperty(FhirSctProperty.EQUIVALENT_CONCEPT, coding, FHIRProperty.CODING_TYPE));
		}
	}

	private void addProperties(Parameters parameters, Concept c, Set<FhirSctProperty> properties,
	                           ConceptAndSystemResult conceptAndSystemResult, List<LanguageDialect> languageDialects) {
		boolean allProperties = properties.contains(FhirSctProperty.ALL_PROPERTIES);

		addRelationshipProperties(parameters, c);
		addEffectiveTimeProperty(parameters, c);
		parameters.addParameter(createProperty(FhirSctProperty.INACTVE, !c.isActive(), FHIRProperty.BOOLEAN_TYPE));
		addModuleProperty(parameters, c, conceptAndSystemResult, languageDialects);

		if (allProperties || properties.contains(FhirSctProperty.SUFFICIENTLY_DEFINED)) {
			Boolean sufficientlyDefined = c.getDefinitionStatusId().equals(Concepts.DEFINED);
			parameters.addParameter(createProperty(FhirSctProperty.SUFFICIENTLY_DEFINED, sufficientlyDefined, FHIRProperty.BOOLEAN_TYPE));
		}

		if (allProperties || properties.contains(FhirSctProperty.NORMAL_FORM_TERSE)) {
			Expression expression = expressionService.getExpression(c, false);
			parameters.addParameter(createProperty(FhirSctProperty.NORMAL_FORM_TERSE, expression.toString(false), FHIRProperty.STRING_TYPE));
		}

		if (allProperties || properties.contains(FhirSctProperty.NORMAL_FORM)) {
			Expression expression = expressionService.getExpression(c, false);
			parameters.addParameter(createProperty(FhirSctProperty.NORMAL_FORM, expression.toString(true), FHIRProperty.STRING_TYPE));
		}
	}

	// Attribute relationships (non-IS-A inferred)
	private void addRelationshipProperties(Parameters parameters, Concept c) {
		for (Relationship rel : c.getRelationships(true, null, null, Concepts.INFERRED_RELATIONSHIP)) {
			if (!Concepts.ISA.equals(rel.getTypeId())) {
				Parameters.ParametersParameterComponent property = new Parameters.ParametersParameterComponent().setName(PROPERTY);
				property.addPart().setName(CODE).setValue(new CodeType(rel.getTypeId()));
				ConceptMini typeConceptMini = rel.getType();
				if (typeConceptMini != null && typeConceptMini.getPt() != null) {
					property.addPart().setName("code-display").setValue(new StringType(typeConceptMini.getPt().getTerm()));
				}
				ConceptMini targetConceptMini = rel.getTarget();
				if (targetConceptMini != null && targetConceptMini.getPt() != null) {
					property.addPart().setName(PART_DESCRIPTION).setValue(new StringType(targetConceptMini.getPt().getTerm()));
				}
				property.addPart().setName(VALUE).setValue(new CodeType(rel.getDestinationId()));
				parameters.addParameter(property);
			}
		}
	}

	// effectiveTime as valueDateTime
	private void addEffectiveTimeProperty(Parameters parameters, Concept c) {
		if (c.getEffectiveTime() == null) {
			return;
		}
		String raw = String.valueOf(c.getEffectiveTime());
		String formatted = raw.length() == 8
				? raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8)
				: raw;
		Parameters.ParametersParameterComponent prop = new Parameters.ParametersParameterComponent().setName(PROPERTY);
		prop.addPart().setName(CODE).setValue(FhirSctProperty.EFFECTIVE_TIME.toCodeType());
		prop.addPart().setName(VALUE).setValue(new DateTimeType(formatted));
		parameters.addParameter(prop);
	}

	private void addModuleProperty(Parameters parameters, Concept c,
	                               ConceptAndSystemResult conceptAndSystemResult, List<LanguageDialect> languageDialects) {
		if (c.getModuleId() == null) {
			return;
		}
		Parameters.ParametersParameterComponent moduleProp = new Parameters.ParametersParameterComponent().setName(PROPERTY);
		moduleProp.addPart().setName(CODE).setValue(FhirSctProperty.MODULE_ID.toCodeType());
		String branchPath = conceptAndSystemResult.codeSystemVersion().getSnomedBranch();
		if (branchPath != null) {
			Map<String, ConceptMini> moduleMinis = snomedConceptService.findConceptMinis(
					branchPath, Collections.singleton(c.getModuleId()), languageDialects).getResultsMap();
			ConceptMini moduleMini = moduleMinis.get(c.getModuleId());
			if (moduleMini != null && moduleMini.getPt() != null) {
				moduleProp.addPart().setName(PART_DESCRIPTION).setValue(new StringType(moduleMini.getPt().getTerm()));
			}
		}
		moduleProp.addPart().setName(VALUE).setValue(new CodeType(c.getModuleId()));
		parameters.addParameter(moduleProp);
	}

	private void addParents(Parameters parameters, Concept c) {
		List<Relationship> parentRels = c.getRelationships(true, Concepts.ISA, null, Concepts.INFERRED_RELATIONSHIP);
		for (Relationship rel : parentRels) {
			Parameters.ParametersParameterComponent property = new Parameters.ParametersParameterComponent().setName(PROPERTY);
			property.addPart().setName(CODE).setValue(FhirSctProperty.PARENT.toCodeType());
			ConceptMini target = rel.getTarget();
			if (target != null && target.getPt() != null) {
				property.addPart().setName(PART_DESCRIPTION).setValue(new StringType(target.getPt().getTerm()));
			}
			property.addPart().setName(VALUE).setValue(new CodeType(rel.getDestinationId()));
			parameters.addParameter(property);
		}
	}

	private void addChildren(Parameters parameters, Collection<String> childIds,
	                         ConceptAndSystemResult conceptAndSystemResult, List<LanguageDialect> languageDialects) {
		if (childIds.isEmpty()) return;
		String branchPath = conceptAndSystemResult.codeSystemVersion().getSnomedBranch();
		Map<String, ConceptMini> minis = branchPath != null
				? snomedConceptService.findConceptMinis(branchPath, childIds, languageDialects).getResultsMap()
				: Collections.emptyMap();
		for (String childId : childIds) {
			Parameters.ParametersParameterComponent property = new Parameters.ParametersParameterComponent().setName(PROPERTY);
			property.addPart().setName(CODE).setValue(FhirSctProperty.CHILD.toCodeType());
			ConceptMini mini = minis.get(childId);
			if (mini != null && mini.getPt() != null) {
				property.addPart().setName(PART_DESCRIPTION).setValue(new StringType(mini.getPt().getTerm()));
			}
			property.addPart().setName(VALUE).setValue(new CodeType(childId));
			parameters.addParameter(property);
		}
	}

	private Parameters.ParametersParameterComponent createProperty(FhirSctProperty propertyName, Object propertyValue, String propertyType) {
		Parameters.ParametersParameterComponent property = new Parameters.ParametersParameterComponent().setName(PROPERTY);
		property.addPart().setName(CODE).setValue(propertyName.toCodeType());
		final String propertyValueString = propertyValue == null ? "" : propertyValue.toString();
		switch (propertyType) {
			case FHIRProperty.CODE_TYPE:
				property.addPart().setName(VALUE).setValue(new CodeType(propertyValueString));
				break;
			case FHIRProperty.CODING_TYPE:
				if (propertyValue instanceof Coding coding) {
					property.addPart().setName(VALUE).setValue(coding);
				} else {
					throw new IllegalArgumentException(propertyValue + " is not of type 'Coding'");
				}
				break;
			case FHIRProperty.BOOLEAN_TYPE:
				property.addPart().setName(VALUE).setValue(new BooleanType((Boolean) propertyValue));
				break;
			default:
				property.addPart().setName(VALUE_STRING).setValue(new StringType(propertyValueString));
		}
		return property;
	}
}
