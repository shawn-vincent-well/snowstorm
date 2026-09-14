package org.snomed.snowstorm.fhir.services;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.QuantityParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.fhir.config.FHIRConstants;
import org.snomed.snowstorm.fhir.domain.FHIRValueSet;
import org.snomed.snowstorm.fhir.domain.SearchFilter;
import org.snomed.snowstorm.fhir.pojo.FHIRCodeValidationRequest;
import org.snomed.snowstorm.fhir.pojo.ValueSetExpansionParameters;
import org.snomed.snowstorm.fhir.repositories.FHIRValueSetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import org.snomed.snowstorm.fhir.pojo.CanonicalUri;

import org.hl7.fhir.instance.model.api.IBase;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.snomed.snowstorm.fhir.services.FHIRHelper.exception;

@Component
public class FHIRValueSetProvider implements IResourceProvider, FHIRConstants {

	private static final String RESOURCE_TYPE_VALUE_SET = "ValueSet";

	/**
	 * The most ValueSets one search can page through.
	 *
	 * Not a number we picked: Elasticsearch refuses a from+size beyond index.max_result_window,
	 * which defaults to 10,000, so this is the store's own ceiling. It is enforced here so the
	 * failure is an explicit OperationOutcome naming the limit rather than a truncated result or
	 * a raw Elasticsearch error out of the depths.
	 */
	public static final int MAX_SEARCHABLE_VALUE_SETS = 10_000;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Value("${snowstorm.rest-api.readonly}")
	private boolean readOnlyMode;

	private final FHIRValueSetRepository valuesetRepository;

	private final FHIRValueSetService valueSetService;

	private final FHIRValueSetFinderService valueSetFinderService;

	private final FhirContext fhirContext;

	private final FHIRHelper fhirHelper;

	public FHIRValueSetProvider(FHIRValueSetRepository valuesetRepository, FHIRValueSetService valueSetService, FHIRValueSetFinderService valueSetFinderService, FhirContext fhirContext, FHIRHelper fhirHelper) {
		this.valuesetRepository = valuesetRepository;
		this.valueSetService = valueSetService;
		this.valueSetFinderService = valueSetFinderService;
		this.fhirContext = fhirContext;
		this.fhirHelper = fhirHelper;
	}

	@Read
	public ValueSet getValueSet(@IdParam IdType id) {
		Optional<FHIRValueSet> valueSetOptional = valuesetRepository.findById(id.getIdPart());
		return valueSetOptional.map(FHIRValueSet::getHapi).orElse(null);
	}

	@Create
	public MethodOutcome createValueSet(@IdParam IdType id, @ResourceParam ValueSet vs) {
		FHIRHelper.readOnlyCheck(readOnlyMode);
		MethodOutcome outcome = new MethodOutcome();
		FHIRValueSet savedVs = valueSetService.createOrUpdateValueset(vs);
		outcome.setId(new IdType(RESOURCE_TYPE_VALUE_SET, savedVs.getId(), vs.getVersion()));
		return outcome;
	}

	@Update
	public MethodOutcome updateValueSet(@IdParam IdType id, @ResourceParam ValueSet vs) {
		FHIRHelper.readOnlyCheck(readOnlyMode);
		try {
			return createValueSet(id, vs);
		} catch (SnowstormFHIRServerResponseException e) {
			throw exception("Failed to update/create valueset '" + vs.getId() + "'", IssueType.EXCEPTION, 400, e);
		}
	}

	@Delete
	public MethodOutcome deleteValueSet(
			@IdParam IdType id,
			@OptionalParam(name="url") UriType url,
			@OptionalParam(name="version") String version) {

		FHIRHelper.readOnlyCheck(readOnlyMode);
		MethodOutcome outcome = new MethodOutcome();
		if (id != null) {
			valuesetRepository.deleteById(id.getIdPart());
			outcome.setId(new IdType(RESOURCE_TYPE_VALUE_SET, id.getIdPart()));
		} else {
			FHIRHelper.required("url", url);
			FHIRHelper.required("version", version);
			valueSetFinderService.find(url.getValueAsString(), version).ifPresent(vs -> {
				valuesetRepository.deleteById(vs.getId());
				outcome.setId(new IdType(RESOURCE_TYPE_VALUE_SET, vs.getId(), version));
			});
		}
		return outcome;
	}

	/**
	 * List the stored ValueSets, honouring the paging and search parameters the endpoint declares.
	 *
	 * This used to return a fully materialised Bundle built from a page size chosen here rather
	 * than by the caller -- PageRequest.of(0, 1_000) on the plain listing, so a client holding
	 * 3,464 value sets saw 1,000 of them, got no next link, and had no way to tell. _count and
	 * _offset were accepted and ignored, and the reported total came from before the in-memory
	 * filter ran, so url=X&version=BOGUS answered "total 2" over an empty entry list.
	 *
	 * Returning an IBundleProvider hands the link building back to HAPI, which is the only party
	 * that knows what the caller asked for.
	 *
	 * The paging itself stays here on purpose, because that is HAPI's contract and not a choice:
	 * when a request carries _offset, ResponseBundleBuilder.offsetBuildResourceList asks the
	 * provider for getResources(0, Integer.MAX_VALUE) and does not trim the result, on the
	 * understanding that a provider declaring @Offset has already returned exactly one page.
	 *
	 * @see <a href="https://www.hl7.org/fhir/valueset.html#search">ValueSet search parameters</a>
	 */
	@Search
	public IBundleProvider findValueSets(
			// Count is fully qualified: the wildcard import of org.hl7.fhir.r4.model also has one.
			@ca.uhn.fhir.rest.annotation.Count Integer count,
			@Offset Integer offset,
			@OptionalParam(name="_id") String id,
			@OptionalParam(name="code") String code,
			@OptionalParam(name="context") TokenParam context,
			@OptionalParam(name="context-quantity") QuantityParam contextQuantity,
			@OptionalParam(name="context-type") String contextType,
			@OptionalParam(name="date") StringParam date,
			@OptionalParam(name="description") StringParam description,
			@OptionalParam(name="expansion") String expansion,
			@OptionalParam(name="identifier") StringParam identifier,
			@OptionalParam(name="jurisdiction") StringParam jurisdiction,
			@OptionalParam(name="name") StringParam name,
			@OptionalParam(name="publisher") StringParam publisher,
			@OptionalParam(name="reference") StringParam reference,
			@OptionalParam(name="status") String status,
			@OptionalParam(name="title") StringParam title,
			@OptionalParam(name="url") UriType url,
			@OptionalParam(name="version") StringParam version) {

		SearchFilter vsFilter = new SearchFilter()
				.withId(id)
				.withCode(code)
				.withContext(context)
				.withContextQuantity(contextQuantity)
				.withContextType(contextType)
				.withDate(date)
				.withDescription(description)
				.withExpansion(expansion)
				.withIdentifier(identifier)
				.withJurisdiction(jurisdiction)
				.withName(name)
				.withPublisher(publisher)
				.withReference(reference)
				.withStatus(status)
				.withTitle(title)
				.withUrl(url)
				.withVersion(version);

		if (url != null) {
			// A canonical url resolves to a handful of versions at most, so the whole set is read
			// and filtered here. The reported total is now the filtered count: it used to be
			// allByUrl.size(), taken before the filter ran, so url=X&version=BOGUS reported a
			// total of 2 alongside zero entries.
			return FHIRPagedSearchResult.of(count, offset, asListing(valuesetRepository.findAllByUrl(url.getValueAsString()).stream()
					.map(FHIRValueSet::getHapi)
					.filter(vs -> vsFilter.apply(vs, fhirHelper))));

		} else if (vsFilter.anySearchParams()) {
			// Every search parameter other than url is applied in memory, so the whole store has
			// to be walked before the matches are known. Elasticsearch will not serve a window
			// past MAX_SEARCHABLE_VALUE_SETS, so if the store has outgrown that, say so rather
			// than filter a truncated slice and present the result as the answer.
			Page<FHIRValueSet> all = valueSetService.findAll(PageRequest.of(0, MAX_SEARCHABLE_VALUE_SETS));
			requireWholeStoreSearchable(all.getTotalElements());
			return FHIRPagedSearchResult.of(count, offset, asListing(all.stream()
					.map(FHIRValueSet::getHapi)
					.filter(vs -> vsFilter.apply(vs, fhirHelper))));

		} else {
			return storedValueSetListing(count, offset);
		}
	}

	/**
	 * Strip compose and collect, for a search result rather than an expansion.
	 *
	 * The fullUrl each entry used to carry is not lost: HAPI sets it from the resource id and the
	 * server base when it builds the bundle, which is where that knowledge belongs.
	 */
	private static List<IBaseResource> asListing(Stream<ValueSet> valueSets) {
		return valueSets
				.map(vs -> {
					vs.setCompose(null);// Remove compose element from ValueSet search/listing
					return (IBaseResource) vs;
				})
				.toList();
	}

	/**
	 * Refuse a search that cannot be answered completely, rather than answer part of it silently.
	 *
	 * Only reachable once the store holds more value sets than Elasticsearch will page through.
	 * A caller who hits this can still reach any individual value set by url.
	 */
	private static void requireWholeStoreSearchable(long storedValueSets) {
		if (storedValueSets > MAX_SEARCHABLE_VALUE_SETS) {
			throw exception(String.format("This server holds %s ValueSets, more than the %s a single search can page " +
							"through, so this search cannot be answered completely. Narrow it with the 'url' parameter.",
					storedValueSets, MAX_SEARCHABLE_VALUE_SETS), IssueType.TOOCOSTLY, 400);
		}
	}

	/**
	 * The unfiltered listing of every stored ValueSet, read from Elasticsearch a window at a time.
	 *
	 * Lazy rather than materialised because that is the point of the change: the old code fixed the
	 * page size at PageRequest.of(0, 1_000) before HAPI ever saw the request, which is what made
	 * _count unimplementable and hid 2,464 of our 3,464 value sets behind a bundle that looked whole.
	 */
	private FHIRPagedSearchResult storedValueSetListing(Integer count, Integer offset) {
		// Counted once, up front. HAPI asks size() repeatedly while building the response and uses
		// it for the next/previous link arithmetic, so it must not move underneath them.
		int total = (int) valueSetService.findAll(PageRequest.of(0, 1)).getTotalElements();
		requireWholeStoreSearchable(total);

		return new FHIRPagedSearchResult(count, offset, total, (fromIndex, toIndex) -> {
			// Elasticsearch pages by from+size, but Pageable only expresses whole pages of equal
			// size and the requested window need not line up with one. So read from the start of
			// the index and slice. toIndex is bounded by MAX_SEARCHABLE_VALUE_SETS through the
			// check above, which is the same bound Elasticsearch puts on from+size anyway.
			List<FHIRValueSet> window = valueSetService.findAll(PageRequest.of(0, toIndex)).getContent();
			return asListing(window.subList(fromIndex, toIndex).stream().map(FHIRValueSet::getHapi));
		});
	}

	@Operation(name = "$expand", idempotent = true)
	public ValueSet expandInstance(
			@IdParam IdType id,
			HttpServletRequest request,
			HttpServletResponse response,
			@ResourceParam String rawBody,
			@OperationParam(name="url") UriType url,
			@OperationParam(name="valueSetVersion") String valueSetVersion,
			@OperationParam(name="context") String context,
			@OperationParam(name="contextDirection") String contextDirection,
			@OperationParam(name="filter") String filter,
			@OperationParam(name="date") String date,
			@OperationParam(name="offset") IntegerType offset,
			@OperationParam(name="count") IntegerType count,
			@OperationParam(name="includeDesignations") BooleanType includeDesignationsType,
			@OperationParam(name="designation") List<String> designations,
			@OperationParam(name="includeDefinition") BooleanType includeDefinition,
			@OperationParam(name="activeOnly") BooleanType activeType,
			@OperationParam(name="excludeNested") BooleanType excludeNested,
			@OperationParam(name="excludeNotForUI") BooleanType excludeNotForUI,
			@OperationParam(name="excludePostCoordinated") BooleanType excludePostCoordinated,
			@OperationParam(name="displayLanguage") String displayLanguage,
			@OperationParam(name="exclude-system") StringType excludeSystem,
			@OperationParam(name="system-version") CanonicalType systemVersion,
			@OperationParam(name="check-system-version") CanonicalType checkSystemVersion,
			@OperationParam(name="force-system-version") CanonicalType forceSystemVersion,
			@OperationParam(name="version") StringType version,
			@OperationParam(name="property") CodeType property,
			@OperationParam(name = "default-valueset-version") CanonicalType versionValueSet)// Invalid parameter
			{

		ValueSetExpansionParameters params;
		if (request.getMethod().equals(RequestMethod.POST.name())) {
			// HAPI doesn't populate the OperationParam values for POST, we parse the body instead.
			Parameters postParams = fhirContext.newJsonParser().parseResource(Parameters.class, rawBody);
			params = FHIRValueSetProviderHelper.getValueSetExpansionParameters(id, postParams.getParameter());
		} else {
			params = FHIRValueSetProviderHelper.getValueSetExpansionParameters(id, url, valueSetVersion, context, contextDirection, filter, date, offset, count,
					includeDesignationsType, designations, includeDefinition, activeType, excludeNested, excludeNotForUI, excludePostCoordinated, displayLanguage,
					excludeSystem, systemVersion, checkSystemVersion, forceSystemVersion, version, property,versionValueSet);
		}
		return valueSetService.expand(params, FHIRHelper.getDisplayLanguage(params.getDisplayLanguage(), request.getHeader(ACCEPT_LANGUAGE_HEADER)));
	}

	@Operation(name = "$expand", idempotent = true)
	public ValueSet expandType(
			HttpServletRequest request,
			HttpServletResponse response,
			@ResourceParam String rawBody,
			@OperationParam(name="url") UriType url,
			@OperationParam(name="valueSetVersion") String valueSetVersion,
			@OperationParam(name="context") String context,
			@OperationParam(name="contextDirection") String contextDirection,
			@OperationParam(name="filter") String filter,
			@OperationParam(name="date") String date,
			@OperationParam(name="offset") IntegerType offset,
			@OperationParam(name="count") IntegerType count,
			@OperationParam(name="includeDesignations") BooleanType includeDesignationsType,
			@OperationParam(name="designation") List<String> designations,
			@OperationParam(name="includeDefinition") BooleanType includeDefinition,
			@OperationParam(name="activeOnly") BooleanType activeType,
			@OperationParam(name="excludeNested") BooleanType excludeNested,
			@OperationParam(name="excludeNotForUI") BooleanType excludeNotForUI,
			@OperationParam(name="excludePostCoordinated") BooleanType excludePostCoordinated,
			@OperationParam(name="displayLanguage") String displayLanguage,
			@OperationParam(name="exclude-system") StringType excludeSystem,
			@OperationParam(name="system-version") CanonicalType systemVersion,
			@OperationParam(name="check-system-version") CanonicalType checkSystemVersion,
			@OperationParam(name="force-system-version") CanonicalType forceSystemVersion,
			@OperationParam(name="version") StringType version,// Invalid parameter
			@OperationParam(name="property") CodeType property,
			@OperationParam(name="default-valueset-version") CanonicalType versionValueSet)
			{
		if (logger.isInfoEnabled()) {
			logger.info(FHIRValueSetProviderHelper.getFullURL(request));
		}
		ValueSetExpansionParameters params;
		if (request.getMethod().equals(RequestMethod.POST.name())) {
			// HAPI doesn't populate the OperationParam values for POST, we parse the body instead.
			List<Parameters.ParametersParameterComponent> parsed = fhirContext.newJsonParser().parseResource(Parameters.class, rawBody).getParameter();
			TxResourceContext.set(FHIRHelper.extractTxResources(parsed));
			params = FHIRValueSetProviderHelper.getValueSetExpansionParameters(null, parsed);
		} else {
			params = FHIRValueSetProviderHelper.getValueSetExpansionParameters(null, url, valueSetVersion, context, contextDirection, filter, date, offset, count,
					includeDesignationsType, designations, includeDefinition, activeType, excludeNested, excludeNotForUI, excludePostCoordinated, displayLanguage,
					excludeSystem, systemVersion, checkSystemVersion, forceSystemVersion, version, property, versionValueSet);
		}
		try {
			return valueSetService.expand(params, request.getHeader(ACCEPT_LANGUAGE_HEADER));
		} finally {
			TxResourceContext.clear();
		}
	}

	@Operation(name="$validate-code", idempotent=true)
	public Parameters validateCodeExplicit(
			@IdParam IdType id,
			HttpServletRequest request,
			HttpServletResponse response,
			@OperationParam(name="url") UriType url,
			@OperationParam(name="context") UriType context,
			@OperationParam(name="valueSet") ValueSet valueSet,
			@OperationParam(name="valueSetVersion") String valueSetVersion,
			@OperationParam(name="code") String code,
			@OperationParam(name="system") UriType system,
			@OperationParam(name="systemVersion") String systemVersion,
			@OperationParam(name="display") String display,
			@OperationParam(name="coding") Coding coding,
			@OperationParam(name="codeableConcept") CodeableConcept codeableConcept,
			@OperationParam(name="date") DateTimeType date,
			@OperationParam(name="abstract") BooleanType abstractBool,
			@OperationParam(name="displayLanguage") String displayLanguage,
			@OperationParam(name="system-version") String systemVersionDeprecated,
			@OperationParam(name="force-system-version") String forceSystemVersionStr,
			@OperationParam(name="check-system-version") String checkSystemVersionStr,
			@OperationParam(name="inferSystem") BooleanType inferSystem,
			@OperationParam(name="lenient-display-validation") BooleanType lenientDisplayValidation,
			@OperationParam(name="valueset-membership-only") BooleanType valueSetMembershipOnly,
			@OperationParam(name="activeOnly") BooleanType activeOnly) {

		// system-version canonical hints (system|version) for resolving versionless includes
		Set<CanonicalUri> defaultSystemVersions = systemVersionDeprecated != null
				? Set.of(CanonicalUri.fromString(systemVersionDeprecated)) : null;
		FHIRCodeValidationRequest codeValidationRequest = new FHIRCodeValidationRequest()
			.withId(id == null ? null : id.getIdPart())
			.withUrl(url)
			.withContext(context)
			.withValueSet(valueSet)
			.withValueSetVersion(valueSetVersion)
			.withCode(code)
			.withSystem(system)
			.withSystemVersion(systemVersion)
			.withDefaultSystemVersions(defaultSystemVersions)
			.withForceSystemVersion(forceSystemVersionStr != null ? CanonicalUri.fromString(forceSystemVersionStr) : null)
			.withCheckSystemVersion(checkSystemVersionStr != null ? CanonicalUri.fromString(checkSystemVersionStr) : null)
			.withDisplay(display)
			.withCoding(coding)
			.withCodeableConcept(codeableConcept)
			.withDate(date)
			.withAbstractBool(abstractBool)
			.withDisplayLanguage(FHIRHelper.getDisplayLanguage(displayLanguage, request.getHeader(ACCEPT_LANGUAGE_HEADER)))
			.withInferSystem(inferSystem)
			.withActiveOnly(activeOnly)
			.withLenientDisplayValidation(lenientDisplayValidation)
			.withValueSetMembershipOnly(valueSetMembershipOnly);
		return valueSetService.validateCode(codeValidationRequest);
	}

	@Operation(name="$validate-code", idempotent=true)
	public Parameters validateCodeImplicit(
			HttpServletRequest request,
			HttpServletResponse response,
			@ResourceParam String rawBody,
			@OperationParam(name="url") UriType url,
			@OperationParam(name="context") UriType context,
			@OperationParam(name="valueSet") ValueSet valueSet,
			@OperationParam(name="valueSetVersion") String valueSetVersion,
			@OperationParam(name="code") String code,
			@OperationParam(name="system") UriType system,
			@OperationParam(name="systemVersion") String systemVersion,
			@OperationParam(name="display") String display,
			@OperationParam(name="coding") Coding coding,
			@OperationParam(name="codeableConcept") CodeableConcept codeableConcept,
			@OperationParam(name="date") DateTimeType date,
			@OperationParam(name="abstract") BooleanType abstractBool,
			@OperationParam(name="displayLanguage") String displayLanguage,
			@OperationParam(name="system-version") String systemVersionDeprecated,
			@OperationParam(name="force-system-version") String forceSystemVersionStr,
			@OperationParam(name="check-system-version") String checkSystemVersionStr,
			@OperationParam(name="inferSystem") BooleanType inferSystem,
			@OperationParam(name="activeOnly") BooleanType activeOnly,
			@OperationParam(name="lenient-display-validation") BooleanType lenientDisplayValidation,
			@OperationParam(name="valueset-membership-only") BooleanType valueSetMembershipOnly,
			@OperationParam(name="default-valueset-version") CanonicalType versionValueSet) {

		if (logger.isInfoEnabled()) {
			logger.info(FHIRValueSetProviderHelper.getFullURL(request));
		}
		if (request.getMethod().equals(RequestMethod.POST.name())) {
			// HAPI doesn't populate the OperationParam values for POST, we parse the body instead.
			List<Parameters.ParametersParameterComponent> parsed = fhirContext.newJsonParser().parseResource(Parameters.class, rawBody).getParameter();
			TxResourceContext.set(FHIRHelper.extractTxResources(parsed));
		}
		// system-version canonical hints (system|version) for resolving versionless includes
		Set<CanonicalUri> defaultSystemVersions = systemVersionDeprecated != null
				? Set.of(CanonicalUri.fromString(systemVersionDeprecated)) : null;
		FHIRCodeValidationRequest codeValidationRequest = new FHIRCodeValidationRequest()
			.withUrl(url)
			.withContext(context)
			.withValueSet(valueSet)
			.withValueSetVersion(valueSetVersion)
			.withCode(code)
			.withSystem(system)
			.withSystemVersion(systemVersion)
			.withDefaultSystemVersions(defaultSystemVersions)
			.withForceSystemVersion(forceSystemVersionStr != null ? CanonicalUri.fromString(forceSystemVersionStr) : null)
			.withCheckSystemVersion(checkSystemVersionStr != null ? CanonicalUri.fromString(checkSystemVersionStr) : null)
			.withDisplay(display)
			.withCoding(coding)
			.withCodeableConcept(codeableConcept)
			.withDate(date)
			.withAbstractBool(abstractBool)
			.withDisplayLanguage(FHIRHelper.getDisplayLanguage(displayLanguage, request.getHeader(ACCEPT_LANGUAGE_HEADER)))
			.withInferSystem(inferSystem)
			.withActiveOnly(activeOnly)
			.withVersionValueSet(versionValueSet)
			.withLenientDisplayValidation(lenientDisplayValidation)
			.withValueSetMembershipOnly(valueSetMembershipOnly);
		try {
			return valueSetService.validateCode(codeValidationRequest);
		} finally {
			TxResourceContext.clear();
		}
	}

	@Operation(name = "$batch-validate-code", idempotent = false)
	public Parameters batchValidateCode(
			HttpServletRequest request,
			HttpServletResponse response,
			@ResourceParam String rawBody) {

		Parameters inputParams = fhirContext.newJsonParser().parseResource(Parameters.class, rawBody);
		List<Parameters.ParametersParameterComponent> allParams = inputParams.getParameter();
		TxResourceContext.set(FHIRHelper.extractTxResources(allParams));

		UriType globalUrl = getBatchParamOfType(allParams, "url", UriType.class);
		BooleanType globalLenient = getBatchParamOfType(allParams, "lenient-display-validation", BooleanType.class);
		String displayLanguage = FHIRHelper.getDisplayLanguage(null, request.getHeader(ACCEPT_LANGUAGE_HEADER));

		Parameters result = new Parameters();
		try {
			for (Parameters.ParametersParameterComponent param : allParams) {
				if ("validation".equals(param.getName())) {
					Resource valResult = processOneValidation((Parameters) param.getResource(), globalUrl, globalLenient, displayLanguage);
					result.addParameter().setName("validation").setResource(valResult);
				}
			}
		} finally {
			TxResourceContext.clear();
		}
		return result;
	}

	private Resource processOneValidation(Parameters valParams, UriType url, BooleanType globalLenient, String displayLanguage) {
		List<Parameters.ParametersParameterComponent> params = valParams.getParameter();

		Coding coding = getBatchParamOfType(params, "coding", Coding.class);
		CodeableConcept codeableConcept = getBatchParamOfType(params, "codeableConcept", CodeableConcept.class);
		String code = getBatchStringParam(params, "code");
		UriType system = getBatchParamOfType(params, "system", UriType.class);
		String display = getBatchStringParam(params, "display");
		BooleanType perLenient = getBatchParamOfType(params, "lenient-display-validation", BooleanType.class);
		BooleanType inferSystem = getBatchParamOfType(params, "inferSystem", BooleanType.class);

		if (coding == null && codeableConcept == null && code == null) {
			OperationOutcome oo = new OperationOutcome();
			oo.addIssue()
				.setSeverity(OperationOutcome.IssueSeverity.ERROR)
				.setCode(IssueType.INVALID)
				.setDetails(new CodeableConcept().setText(
					"Unable to find code to validate (looked for coding | codeableConcept | code+system | code+inferSystem in parameters"));
			return oo;
		}

		try {
			return valueSetService.validateCode(new FHIRCodeValidationRequest()
				.withUrl(url)
				.withCoding(coding)
				.withCodeableConcept(codeableConcept)
				.withCode(code)
				.withSystem(system)
				.withDisplay(display)
				.withLenientDisplayValidation(perLenient != null ? perLenient : globalLenient)
				.withInferSystem(inferSystem)
				.withDisplayLanguage(displayLanguage));
		} catch (SnowstormFHIRServerResponseException e) {
			return e.getOperationOutcome();
		}
	}

	private <T extends IBase> T getBatchParamOfType(List<Parameters.ParametersParameterComponent> params, String name, Class<T> type) {
		return params.stream()
			.filter(p -> name.equals(p.getName()) && type.isInstance(p.getValue()))
			.map(p -> type.cast(p.getValue()))
			.findFirst()
			.orElse(null);
	}

	private String getBatchStringParam(List<Parameters.ParametersParameterComponent> params, String name) {
		return params.stream()
			.filter(p -> name.equals(p.getName()) && p.getValue() instanceof PrimitiveType)
			.map(p -> ((PrimitiveType<?>) p.getValue()).getValueAsString())
			.findFirst()
			.orElse(null);
	}

	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return ValueSet.class;
	}
}
