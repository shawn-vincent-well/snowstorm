package org.snomed.snowstorm.fhir.services;


import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.snomed.snowstorm.core.data.domain.CodeSystem;
import org.snomed.snowstorm.core.data.domain.CodeSystemVersion;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.domain.ReferenceSetMember;
import org.snomed.snowstorm.core.data.services.CodeSystemService;
import org.snomed.snowstorm.core.data.services.ConceptService;
import org.snomed.snowstorm.core.data.services.ReferenceSetMemberService;
import org.snomed.snowstorm.core.data.services.pojo.MemberSearchRequest;
import org.snomed.snowstorm.core.pojo.LanguageDialect;
import org.snomed.snowstorm.fhir.config.FHIRConceptMapImplicitConfig;
import org.snomed.snowstorm.fhir.domain.*;
import org.snomed.snowstorm.fhir.pojo.FHIRCodeSystemVersionParams;
import org.snomed.snowstorm.fhir.pojo.FHIRSnomedConceptMapConfig;
import org.snomed.snowstorm.fhir.repositories.FHIRConceptMapRepository;
import org.snomed.snowstorm.fhir.repositories.FHIRMapElementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Comparator.*;
import static co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.*;
import static io.kaicode.elasticvc.helper.QueryHelper.*;
import static org.snomed.snowstorm.core.util.CollectionUtils.orEmpty;
import static org.snomed.snowstorm.fhir.config.FHIRConstants.SNOMED_URI;
import static org.snomed.snowstorm.fhir.services.FHIRHelper.exception;

@Service
public class FHIRConceptMapService {

	public static final String WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX = "?fhir_vs";

	private static final PageRequest PAGE_OF_ONE_THOUSAND = PageRequest.of(0, 1_000);

	private final FHIRConceptMapRepository conceptMapRepository;

	private final ElasticsearchOperations elasticsearchOperations;

	private final FHIRMapElementRepository mapElementRepository;

	private final FHIRCodeSystemService fhirCodeSystemService;

	private final CodeSystemService codeSystemService;

	private final ReferenceSetMemberService snomedRefsetMemberService;

	private final ConceptService snomedConceptService;

	private final FHIRConceptMapImplicitConfig implicitMapConfig;

	private final FHIRConceptService conceptService;

	private final FHIRSnomedModelTermCache snomedModelTermCache;

	// Implicit ConceptMaps - format http://snomed.info/sct[/(module)[/version/(version)]]?fhir_cm=(sctid)
	private List<FHIRSnomedConceptMapConfig> snomedMaps;

	// Map of SNOMED CT map correlation concepts to FHIR equivalence codes - http://hl7.org/fhir/concept-map-equivalence
	private Map<String, Enumerations.ConceptMapEquivalence> snomedCorrelationToFhirEquivalenceMap;

	public FHIRConceptMapService(FHIRConceptMapRepository conceptMapRepository, ElasticsearchOperations elasticsearchOperations, FHIRMapElementRepository mapElementRepository, FHIRCodeSystemService fhirCodeSystemService, CodeSystemService codeSystemService, ReferenceSetMemberService snomedRefsetMemberService, ConceptService snomedConceptService, FHIRConceptMapImplicitConfig implicitMapConfig, FHIRConceptService conceptService, FHIRSnomedModelTermCache snomedModelTermCache) {
		this.conceptMapRepository = conceptMapRepository;
		this.elasticsearchOperations = elasticsearchOperations;
		this.mapElementRepository = mapElementRepository;
		this.fhirCodeSystemService = fhirCodeSystemService;
		this.codeSystemService = codeSystemService;
		this.snomedRefsetMemberService = snomedRefsetMemberService;
		this.snomedConceptService = snomedConceptService;
		this.implicitMapConfig = implicitMapConfig;
		this.conceptService = conceptService;
		this.snomedModelTermCache = snomedModelTermCache;
	}

	@PostConstruct
	public void init() {
		snomedMaps = implicitMapConfig.getImplicitMaps();
		snomedCorrelationToFhirEquivalenceMap = implicitMapConfig.getSnomedCorrelationToFhirEquivalenceMap();
	}

	public FHIRConceptMap createOrUpdate(FHIRConceptMap conceptMap) {
		// FHIR ConceptMap canonical is `url|version` and both are required for persistence.
		String url = conceptMap.getUrl();
		if (url == null || url.isBlank()) {
			throw exception("ConceptMap 'url' is required (canonical is `url|version`).", OperationOutcome.IssueType.INVARIANT, 400);
		}

		String version = conceptMap.getVersion();
		if (version == null || version.isBlank()) {
			throw exception("ConceptMap 'version' is required (canonical is `url|version`).", OperationOutcome.IssueType.INVARIANT, 400);
		}

		if (url.contains("?fhir_cm")) {
			throw exception("ConceptMap url must not contain 'fhir_cm', this is reserved for implicit concept maps.", OperationOutcome.IssueType.INVARIANT, 400);
		}

		// Delete existing maps with the same URL and version
		conceptMapRepository.findAllByUrl(conceptMap.getUrl())
				.stream().filter(map -> conceptMap.getVersion().equals(map.getVersion()))
						.forEach(map -> {
							// Delete map group elements
							for (FHIRConceptMapGroup mapGroup : map.getGroup()) {
								if (mapGroup.getElement() != null) {
									mapElementRepository.deleteAll(mapGroup.getElement());
								}
							}
							conceptMapRepository.delete(map);
						});

		// Save concept map and groups
		FHIRConceptMap saved = conceptMapRepository.save(conceptMap);
		for (FHIRConceptMapGroup mapGroup : conceptMap.getGroup()) {
			// Save elements within each group
			mapElementRepository.saveAll(mapGroup.getElement());
		}
		return saved;
	}

	public FHIRConceptMap findByIdWithGroups(String idPart) {
		Optional<FHIRConceptMap> conceptMap = conceptMapRepository.findById(idPart);
		if (conceptMap.isPresent()) {
			FHIRConceptMap map = conceptMap.get();
			for (FHIRConceptMapGroup group : orEmpty(map.getGroup())) {
				List<FHIRMapElement> elements = mapElementRepository.findAllByGroupId(group.getGroupId());
				group.setElement(elements);
			}
			return map;
		}

		return null;
	}

	public List<FHIRConceptMap> findAll() {
		// Load first 1000 until we can figure out pagination
		List<FHIRConceptMap> maps = new ArrayList<>(hasAnyImportedSnomedVersion() ? getSnomedMaps() : List.of());
		PageRequest pageRequest = PageRequest.of(0, PAGE_OF_ONE_THOUSAND.getPageSize() - maps.size());
		maps.addAll(conceptMapRepository.findAll(pageRequest).getContent());
		return maps;
	}

	private boolean hasAnyImportedSnomedVersion() {
		for (CodeSystem edition : codeSystemService.findAll()) {
			CodeSystemVersion version = codeSystemService.findLatestImportedVersion(edition.getShortName());
			if (version != null && !CodeSystemService.isEmpty2000Version(version)) {
				return true;
			}
		}
		return false;
	}

	private List<FHIRConceptMap> getSnomedMaps() {
		List<FHIRConceptMap> generatedMaps = new ArrayList<>();
		for (FHIRSnomedConceptMapConfig snomedMap : snomedMaps) {
			String refsetId = snomedMap.getReferenceSetId();

			FHIRConceptMap map = new FHIRConceptMap();
			map.setId("snomed_implicit_map_" + refsetId);
			map.setUrl("http://snomed.info/sct?fhir_cm=" + refsetId);
			map.setName(snomedMap.getName());
			map.setSourceUri(snomedMap.getSourceSystem() + WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX);
			map.setTargetUri(snomedMap.getTargetSystem() + WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX);

			// For internal use
			map.setImplicitSnomedMap(true);
			map.setSnomedRefsetId(refsetId);
			map.setSnomedRefsetEquivalence(snomedMap.getRefsetEquivalence());

			generatedMaps.add(map);
		}
		return generatedMaps;
	}

	Collection<FHIRConceptMap> findMaps(String url, Coding coding, String targetSystem, String sourceValueSet, String targetValueSet) {
		return findMaps(url, coding, targetSystem, sourceValueSet, targetValueSet, false);
	}

	/**
	 * Map SELECTION has to flip for a reverse translate, not just group filtering.
	 *
	 * Found by running the conformance suite rather than by reading: reverse translate returned
	 * "No suitable map found." because this filter matched the coding's system against
	 * GROUP_SOURCE before findMapElements was ever reached. Flipping the group filter
	 * inside findMapElements was necessary but not sufficient -- the candidate maps were
	 * already gone.
	 */
	Collection<FHIRConceptMap> findMaps(String url, Coding coding, String targetSystem, String sourceValueSet, String targetValueSet, boolean reverse) {
		BoolQuery.Builder query = bool();
		List<Predicate<FHIRConceptMap>> snomedPredicates = new ArrayList<>();
		if (url != null) {
			if (FHIRHelper.isSnomedUri(url) && url.contains("?")) {
				url = SNOMED_URI + url.substring(url.indexOf("?"));
			}
			query.must(termQuery(FHIRConceptMap.Fields.URL, url));
			String finalUrl = url;
			snomedPredicates.add(map -> finalUrl.equals(map.getUrl()));
		}
		if (coding != null) {
			// When reversing, the code we were given lives on the map's TARGET side.
			query.must(termQuery(reverse ? FHIRConceptMap.Fields.GROUP_TARGET : FHIRConceptMap.Fields.GROUP_SOURCE, coding.getSystem()));
			snomedPredicates.add(map -> reverse
					? (map.getTargetUri() == null || map.getTargetUri().startsWith(coding.getSystem().replace("/xsct", "/sct")))
					: (map.getSourceUri() == null || map.getSourceUri().startsWith(coding.getSystem().replace("/xsct", "/sct"))));
		}
		if (targetSystem != null) {
			// Reversed, the answer comes off the map's SOURCE side, so that is what
			// targetSystem narrows. Left on GROUP_TARGET it contradicts the coding clause
			// above -- both constraining the same field to different systems -- and no map
			// can ever be selected, which surfaces as "No suitable map found" for a map that
			// is sitting right there.
			query.must(termQuery(reverse ? FHIRConceptMap.Fields.GROUP_SOURCE : FHIRConceptMap.Fields.GROUP_TARGET, targetSystem));
			snomedPredicates.add(map -> reverse
					? map.getSourceUri().equals(targetSystem + WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX)
					: map.getTargetUri().equals(targetSystem + WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX));
		}
		if (sourceValueSet != null) {
			query.must(bool(b -> b
					// Map either has no source (value set) or it matches the param
					.should(bool(bq -> bq.mustNot(existsQuery(FHIRConceptMap.Fields.SOURCE))))
					.should(termQuery(FHIRConceptMap.Fields.SOURCE, sourceValueSet))
			));
			snomedPredicates.add(map -> map.getSourceUri().equals(sourceValueSet));
		}
		if (targetValueSet != null) {
			query.must(bool(b -> b
					// Map either has no target (value set) or it matches the param
					.should(bool(bq -> bq.mustNot(existsQuery(FHIRConceptMap.Fields.TARGET))))
					.should(termQuery(FHIRConceptMap.Fields.TARGET, targetValueSet))
			));
			snomedPredicates.add(map -> map.getTargetUri().equals(targetValueSet));
		}
		NativeQueryBuilder queryBuilder = new NativeQueryBuilder()
				.withQuery(query.build()._toQuery())
				.withPageable(PageRequest.of(0, 100));

		// Grab maps from store
		List<FHIRConceptMap> maps = new ArrayList<>(searchForList(queryBuilder, FHIRConceptMap.class));

		// Grab generated snomed maps when a SNOMED CT release is loaded
		if (hasAnyImportedSnomedVersion()) {
			maps.addAll(getSnomedMaps().stream()
					.filter(map -> snomedPredicates.stream().allMatch(predicate -> predicate.test(map))).toList());
		}

		return maps;
	}

	public Collection<FHIRMapElement> findMapElements(FHIRConceptMap map, Coding coding, String targetSystem, List<LanguageDialect> languageDialects) {
		return findMapElements(map, coding, targetSystem, languageDialects, false);
	}

	/**
	 * `reverse=true` support, which was previously answered with notSupported.
	 *
	 * A reverse translate asks the map backwards -- given a target-side code, which source
	 * concepts map to it. That is the direction a consumer needs when reading data that was
	 * already coded in the target system, and the stored map holds both halves either way,
	 * so refusing it was a gap rather than a missing capability.
	 *
	 * Group filtering flips too: forward matches on `group.source`, reverse on
	 * `group.target`. Getting that wrong returns plausible-looking results from the wrong
	 * groups, which is worse than an error.
	 */
	public Collection<FHIRMapElement> findMapElements(FHIRConceptMap map, Coding coding, String targetSystem,
			List<LanguageDialect> languageDialects, boolean reverse) {
		if (map.isImplicitSnomedMap()) {
			return generateImplicitSnomedMapElements(map, coding, targetSystem, languageDialects, reverse);
		}

		List<FHIRConceptMapGroup> groups = map.getGroup().stream()
				.filter(group -> reverse
						? group.getTarget().equals(coding.getSystem())
						: group.getSource().equals(coding.getSystem()))
				.filter(group -> targetSystem == null || (reverse
						? group.getSource().equals(targetSystem)
						: group.getTarget().equals(targetSystem)))
				.toList();
		BoolQuery.Builder query = bool()
				.must(termsQuery(FHIRMapElement.Fields.GROUP_ID, groups.stream().map(FHIRConceptMapGroup::getGroupId).toList()))
				// TARGET_CODE is "target.code.keyword": target is a nested object and the
				// analysed field will not term-match an exact code.
				.must(reverse
						? termQuery(FHIRMapElement.Fields.TARGET_CODE, coding.getCode())
						: termQuery(FHIRMapElement.Fields.CODE, coding.getCode()));
		NativeQueryBuilder queryBuilder = new NativeQueryBuilder()
				.withQuery(query.build()._toQuery())
				.withPageable(PAGE_OF_ONE_THOUSAND);
		List<FHIRMapElement> found = searchForList(queryBuilder, FHIRMapElement.class);
		if (!reverse) {
			return found;
		}
		// Restate each hit the way the caller consumes it: the element's own code is now
		// the ANSWER, so it moves into the target slot. Keeps response building in the
		// provider identical for both directions.
		return found.stream()
				.map(element -> new FHIRMapElement()
						.setCode(coding.getCode())
						.setTarget(Collections.singletonList(
								new FHIRMapTarget(element.getCode(), reverseEquivalence(element), element.getDisplay()))))
				.toList();
	}

	/**
	 * A reversed mapping is only as strong as the forward one, and not always even that:
	 * `narrower` reversed is `wider`, and vice versa. Everything else passes through --
	 * equal/equivalent/unmatched are direction-neutral, and guessing at the rest would
	 * overstate what the map actually asserts.
	 */
	private String reverseEquivalence(FHIRMapElement element) {
		if (element.getTarget() == null || element.getTarget().isEmpty()) {
			return null;
		}
		String forward = element.getTarget().get(0).getEquivalence();
		if ("narrower".equals(forward)) {
			return "wider";
		}
		if ("wider".equals(forward)) {
			return "narrower";
		}
		return forward;
	}

	private Collection<FHIRMapElement> generateImplicitSnomedMapElements(FHIRConceptMap map, Coding coding, String targetSystem,
			List<LanguageDialect> languageDialects, boolean reverse) {
		boolean snomedOnMapSource = FHIRHelper.isSnomedUri(map.getSourceUri());
		boolean snomedOnMapTarget = FHIRHelper.isSnomedUri(map.getTargetUri());
		// This path is NOT directionally symmetric on its own, though it reads as though it
		// might be. Both the field it searches and the field it reads the answer out of are
		// chosen from the shape of the MAP, while what actually decides them is which side the
		// CALLER supplied a code for. Those coincide only going forwards.
		//
		// So the two sides swap roles under reversal, once, here: below this point "source"
		// means the side the caller supplied and "target" means the side being answered with,
		// and the rest of the method needs no further knowledge of the direction.
		boolean hasSnomedSource = reverse ? snomedOnMapTarget : snomedOnMapSource;
		boolean hasSnomedTarget = reverse ? snomedOnMapSource : snomedOnMapTarget;
		// The reference set being read lives in SNOMED, so the version to query has to be
		// resolved from the MAP's SNOMED side. Resolving it from the supplied coding works
		// only while the caller happens to be holding a SNOMED code: for a reverse translate,
		// or for any map whose SNOMED side is the target, the coding names a different code
		// system entirely, whose getSnomedBranch() is null -- and findMembers rejects a null
		// branch with "The path argument is required".
		String snomedUri = snomedOnMapSource ? map.getSourceUri() : map.getTargetUri();
		FHIRCodeSystemVersionParams versionParams =
				FHIRHelper.getCodeSystemVersionParams((IdType) null, null, null, new Coding().setSystem(snomedUri));
		FHIRCodeSystemVersion snomedVersion = fhirCodeSystemService.findCodeSystemVersionOrThrow(versionParams);

		map.setUrl(map.getUrl().replace(SNOMED_URI + "?", snomedVersion.getVersion() + "?"));

		MemberSearchRequest memberSearchRequest = new MemberSearchRequest()
				.referenceSet(map.getSnomedRefsetId())
				.active(true);
		if (!hasSnomedSource) {
			memberSearchRequest.additionalField(ReferenceSetMember.AssociationFields.MAP_TARGET, coding.getCode());
		} else {
			memberSearchRequest.referencedComponentId(coding.getCode());
		}
		Page<ReferenceSetMember> members = snomedRefsetMemberService.findMembers(snomedVersion.getSnomedBranch(), memberSearchRequest, PAGE_OF_ONE_THOUSAND);

		// Collect map targets for filling terms
		Map<String, List<FHIRMapTarget>> mapTargetsByCode = new HashMap<>();

		Comparator<ReferenceSetMember> mapComparator =
				comparing(ReferenceSetMember::getMapGroup, Comparator.nullsFirst(naturalOrder()))
						.thenComparing(ReferenceSetMember::getMapPriority, Comparator.nullsFirst(naturalOrder()));

		List<FHIRMapElement> generatedElements = members.stream()
				.sorted(mapComparator)
				.map(referenceSetMember -> buildImplicitSnomedMapElement(referenceSetMember, map, coding,
						hasSnomedSource, hasSnomedTarget, snomedVersion, languageDialects, mapTargetsByCode))
				.filter(Objects::nonNull)
				.filter(element -> element.getTarget().get(0).getCode() != null)
				.toList();

		// Grab target display terms
		fillMapTargetDisplayTerms(mapTargetsByCode, hasSnomedTarget, targetSystem, snomedVersion, languageDialects);

		return generatedElements;
	}

	private FHIRMapElement buildImplicitSnomedMapElement(ReferenceSetMember referenceSetMember, FHIRConceptMap map, Coding coding,
			boolean hasSnomedSource, boolean hasSnomedTarget, FHIRCodeSystemVersion snomedVersion,
			List<LanguageDialect> languageDialects, Map<String, List<FHIRMapTarget>> mapTargetsByCode) {
		String targetCode = getTargetCode(hasSnomedSource, hasSnomedTarget, referenceSetMember);
		if (targetCode == null) return null;
		String equivalence = map.getSnomedRefsetEquivalence();
		FHIRMapTarget mapTarget = new FHIRMapTarget(targetCode, equivalence, null);
		mapTargetsByCode.computeIfAbsent(targetCode, key -> new ArrayList<>()).add(mapTarget);
		String message = null;
		String mapGroup = referenceSetMember.getAdditionalField("mapGroup");
		if (mapGroup != null) {
			String mapPriority = referenceSetMember.getAdditionalField("mapPriority");
			String mapRule = referenceSetMember.getAdditionalField("mapRule");
			String mapAdvice = referenceSetMember.getAdditionalField("mapAdvice");
			String correlationId = referenceSetMember.getAdditionalField("correlationId");
			Enumerations.ConceptMapEquivalence mapEquivalence = snomedCorrelationToFhirEquivalenceMap.get(correlationId);
			mapTarget.setEquivalence(mapEquivalence != null ? mapEquivalence.toCode() : null);
			String mapCategoryId = referenceSetMember.getAdditionalField("mapCategoryId");
			String mapCategoryMessage = "";

			// mapCategoryId null for complex map, only used in extended map
			if (mapCategoryId != null) {
				String mapCategoryTerm = snomedModelTermCache.getSnomedTerm(mapCategoryId, snomedVersion, languageDialects);
				mapCategoryMessage = format(", Map Category:'%s'", mapCategoryTerm);
			}

			message = format("Please observe the following map advice. Group:%s, Priority:%s, Rule:%s, Advice:'%s'%s.",
					mapGroup, mapPriority, mapRule, mapAdvice, mapCategoryMessage);
		}
		return new FHIRMapElement()
				.setCode(coding.getCode())
				.setTarget(Collections.singletonList(mapTarget))
				.setMessage(message);
	}

	private void fillMapTargetDisplayTerms(Map<String, List<FHIRMapTarget>> mapTargetsByCode, boolean hasSnomedTarget,
			String targetSystem, FHIRCodeSystemVersion snomedVersion, List<LanguageDialect> languageDialects) {
		if (mapTargetsByCode.isEmpty()) {
			return;
		}
		if (hasSnomedTarget) {
			Map<String, ConceptMini> conceptMiniMap = snomedConceptService.findConceptMinis(snomedVersion.getSnomedBranch(), mapTargetsByCode.keySet(), languageDialects)
					.getResultsMap();
			for (Map.Entry<String, ConceptMini> entry : conceptMiniMap.entrySet()) {
				mapTargetsByCode.get(entry.getKey()).forEach(mapTarget -> mapTarget.setDisplay(entry.getValue().getPt().getTerm()));
			}
		} else {
			Map<String, String> codeDisplayTerms = getCodeDisplayTerms(mapTargetsByCode.keySet(), targetSystem);
			for (Map.Entry<String, String> entry : codeDisplayTerms.entrySet()) {
				mapTargetsByCode.get(entry.getKey()).forEach(mapTarget -> mapTarget.setDisplay(entry.getValue()));
			}
		}
	}

	public Set<FHIRSnomedConceptMapConfig> getConfiguredMapsWithNonSnomedTarget(Set<String> refsetIds) {
		return snomedMaps.stream()
				.filter(map -> refsetIds.contains(map.getReferenceSetId()))
				.filter(map -> !FHIRHelper.isSnomedUri(map.getTargetSystem()))
				.collect(Collectors.toSet());
	}

	@NotNull
	public Map<String, String> getCodeDisplayTerms(Set<String> codes, String systemUrl) {
		if (codes == null || codes.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> codeDisplayTerms = new HashMap<>();
		FHIRCodeSystemVersion targetCodeSystemLatestVersion = fhirCodeSystemService.findCodeSystemVersion(new FHIRCodeSystemVersionParams(systemUrl));
		if (targetCodeSystemLatestVersion != null) {
			Page<FHIRConcept> targetConcepts = conceptService.findConcepts(codes, targetCodeSystemLatestVersion, PageRequest.of(0, 1_000));
			for (FHIRConcept targetConcept : targetConcepts.getContent()) {
				codeDisplayTerms.put(targetConcept.getCode(), targetConcept.getDisplay());
			}
		}
		return codeDisplayTerms;
	}

	private String getTargetCode(boolean hasSnomedSource, boolean hasSnomedTarget, ReferenceSetMember referenceSetMember) {
		String targetCode;
		if (hasSnomedTarget) {
			if (hasSnomedSource) {
				// Association refsets use targetComponentId
				targetCode = referenceSetMember.getAdditionalField(ReferenceSetMember.AssociationFields.TARGET_COMP_ID);
			} else {
				targetCode = referenceSetMember.getReferencedComponentId();
			}
		} else {
			// Target is non-snomed code system
			targetCode = referenceSetMember.getAdditionalField(ReferenceSetMember.AssociationFields.MAP_TARGET);
			if (targetCode == null) {
				// Attribute value refsets use valueId
				targetCode = referenceSetMember.getAdditionalField(ReferenceSetMember.AssociationFields.VALUE_ID);
			}
		}
		return targetCode;
	}

	@NotNull
	private <T> List<T> searchForList(NativeQueryBuilder queryBuilder, Class<T> clazz) {
		return elasticsearchOperations.search(queryBuilder.build(), clazz).stream()
				.map(SearchHit::getContent).toList();
	}
}
