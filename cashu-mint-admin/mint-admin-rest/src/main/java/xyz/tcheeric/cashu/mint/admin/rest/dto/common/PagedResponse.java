package xyz.tcheeric.cashu.mint.admin.rest.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Paginated response envelope for list endpoints.
 */
@Schema(description = "Paginated list response.")
public record PagedResponse<T>(
        @Schema(description = "Items for the current page") List<T> items,
        @Schema(description = "Current page number (zero-indexed)", example = "0") int page,
        @Schema(description = "Page size", example = "20") int size,
        @Schema(description = "Total number of items across all pages", example = "142") long totalItems,
        @Schema(description = "Total number of pages", example = "8") int totalPages
) {

    public static <T> PagedResponse<T> of(final List<T> allItems, final int page, final int size) {
        final int effectiveSize = Math.max(1, Math.min(size, 100));
        final long totalItems = allItems.size();
        final int totalPages = (int) Math.ceil((double) totalItems / effectiveSize);
        final int fromIndex = Math.min(page * effectiveSize, allItems.size());
        final int toIndex = Math.min(fromIndex + effectiveSize, allItems.size());
        final List<T> items = allItems.subList(fromIndex, toIndex);
        return new PagedResponse<>(items, page, effectiveSize, totalItems, totalPages);
    }
}
