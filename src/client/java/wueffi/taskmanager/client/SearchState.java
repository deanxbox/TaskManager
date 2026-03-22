package wueffi.taskmanager.client;

import java.util.Locale;

final class SearchState {

    private SearchState() {
    }

    static boolean matchesCombinedSearch(String haystack, String globalQuery, String localQuery) {
        String normalizedHaystack = haystack == null ? "" : haystack.toLowerCase(Locale.ROOT);
        return matchesQuery(normalizedHaystack, globalQuery) && matchesQuery(normalizedHaystack, localQuery);
    }

    static boolean matchesQuery(String haystack, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalizedHaystack = haystack == null ? "" : haystack.toLowerCase(Locale.ROOT);
        String[] parts = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        for (String part : parts) {
            if (!part.isBlank() && !normalizedHaystack.contains(part)) {
                return false;
            }
        }
        return true;
    }
}
