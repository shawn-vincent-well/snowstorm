package org.snomed.snowstorm.fhir.services;

import ca.uhn.fhir.rest.api.server.IBundleProvider;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * One page of a FHIR search result, for a provider that has declared @Count and @Offset.
 *
 * Shared by the ValueSet, CodeSystem and ConceptMap searches because all three had the same hole:
 * _offset was accepted and ignored, so a request for one page of one returned the entire result
 * set. That is not HAPI failing to page. It is HAPI's contract: when a request carries _offset,
 * ResponseBundleBuilder.offsetBuildResourceList asks the provider for getResources(0,
 * Integer.MAX_VALUE) and does not trim what comes back, on the understanding that a provider
 * declaring @Offset has already returned exactly the page that was asked for. A provider that
 * ignores @Offset therefore hands back everything it has, and HAPI serialises all of it under a
 * next link that says otherwise.
 *
 * So the window arithmetic lives here, and size() deliberately reports the whole matched set rather
 * than this page, because that is what the bundle total means and what HAPI's next/previous link
 * arithmetic is computed against.
 *
 * This is a private HAPI internal, so it is not in the javadoc -- but it is not folklore either:
 * hapi-fhir is Apache 2.0 and the branch above is readable in its own source, pinned at the
 * version this project builds against (hapi-fhir-server 7.6.0):
 * https://github.com/hapifhir/hapi-fhir/blob/v7.6.0/hapi-fhir-server/src/main/java/ca/uhn/fhir/rest/server/method/ResponseBundleBuilder.java
 * Unchanged on HAPI master as of 2026-09-14. FHIRSearchOffsetTest fails loudly if it changes.
 */
class FHIRPagedSearchResult implements IBundleProvider {

	/** Reads an absolute window of the matched set. fromIndex is always strictly below toIndex. */
	interface Window {
		List<IBaseResource> fetch(int fromIndex, int toIndex);
	}

	private final int pageOffset;
	private final int pageSize;
	private final int total;
	private final Window window;

	FHIRPagedSearchResult(Integer count, Integer offset, int total, Window window) {
		// A negative _count or _offset is a client error that HAPI has already accepted by the time
		// we are called. Clamping to zero is the only reading that cannot produce a window running
		// off the front of the result set, and an empty page is at least a visible answer.
		this.pageOffset = offset == null ? 0 : Math.max(0, offset);
		this.pageSize = count == null ? Integer.MAX_VALUE : Math.max(0, count);
		this.total = total;
		this.window = window;
	}

	/** For matches already read into memory. */
	static FHIRPagedSearchResult of(Integer count, Integer offset, List<IBaseResource> matches) {
		return new FHIRPagedSearchResult(count, offset, matches.size(), matches::subList);
	}

	@Override
	public List<IBaseResource> getResources(int fromIndex, int toIndex) {
		// toIndex is Integer.MAX_VALUE whenever the request carried _offset, so the page size has to
		// be applied here as well as the offset. Long arithmetic throughout: pageOffset + MAX_VALUE
		// is exactly the sum that overflows.
		long start = (long) pageOffset + fromIndex;
		long end = Math.min((long) pageOffset + Math.min((long) toIndex, pageSize), total);
		if (start >= end) {
			return Collections.emptyList();
		}
		return window.fetch((int) start, (int) end);
	}

	@Override
	public Integer size() {
		return total;
	}

	@Override
	public IPrimitiveType<Date> getPublished() {
		return null;
	}

	@Override
	public String getUuid() {
		return null;
	}

	@Override
	public Integer preferredPageSize() {
		// Null defers to HAPI, which falls back to size() when the request carries no _count. That
		// keeps the historical behaviour of a search with no paging parameters: everything, in one
		// bundle, with nothing withheld.
		return null;
	}
}
