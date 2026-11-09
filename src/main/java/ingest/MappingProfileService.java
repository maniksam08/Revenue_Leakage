package ingest;

import entity.MappingProfile;
import enums.ReportType;
import repository.MappingProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MappingProfileService {

    private static final Pattern PARTNER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final MappingProfileRepository repository;

    /** Result of applying a saved profile to a file's headers; {@code complete()} means safe for automated ingestion. */
    public record ResolvedMapping(Long profileId, int version, int preambleRows,
                                  Map<CanonicalField, Integer> columnIndexByField, List<String> missingHeaders) {
        public boolean complete() { return missingHeaders.isEmpty(); }
    }

    public static String normalizePartner(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!PARTNER_ID.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "partnerId must be 1-64 chars of letters, digits, '.', '_' or '-'");
        }
        return id;
    }

    @Transactional(readOnly = true)
    public Optional<MappingProfile> findActive(String partnerId, ReportType type) {
        return repository.findFirstByPartnerIdAndReportTypeAndActiveTrue(normalizePartner(partnerId), type);
    }

    @Transactional(readOnly = true)
    public List<MappingProfile> history(String partnerId, ReportType type) {
        return repository.findByPartnerIdAndReportTypeOrderByVersionDesc(normalizePartner(partnerId), type);
    }

    /**
     * Persists the user-approved mapping as a new active version. Re-approving an identical mapping is a no-op
     * (returns the current version) so double-clicks and retries don't create noise.
     */
    @Transactional
    public MappingProfile approve(String partnerIdRaw, ReportType type, Map<String, String> mappings,
                                  int preambleRows, String approvedBy) {
        String partnerId = normalizePartner(partnerIdRaw);
        Map<String, String> clean = validate(type, mappings);

        Optional<MappingProfile> current = repository.findFirstByPartnerIdAndReportTypeAndActiveTrue(partnerId, type);
        if (current.isPresent() && current.get().getColumnMappings().equals(clean)
                && current.get().getPreambleRows() == preambleRows) {
            return current.get();
        }

        repository.deactivateActive(partnerId, type);
        int version = repository.maxVersion(partnerId, type) + 1;
        try {
            return repository.saveAndFlush(MappingProfile.builder()
                    .partnerId(partnerId).reportType(type).version(version).active(true)
                    .columnMappings(clean).preambleRows(preambleRows).approvedBy(approvedBy)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another approval for this partner and report type was saved at the same time; retry", e);
        }
    }

    /** For automated ingestion: map a new file's headers through the partner's active profile. */
    @Transactional(readOnly = true)
    public Optional<ResolvedMapping> resolveActive(String partnerId, ReportType type, List<String> fileHeaders) {
        return findActive(partnerId, type).map(p -> resolve(p, fileHeaders));
    }

    public ResolvedMapping resolve(MappingProfile profile, List<String> fileHeaders) {
        Map<String, Integer> indexByKey = new HashMap<>();
        for (int i = 0; i < fileHeaders.size(); i++) {
            indexByKey.putIfAbsent(ColumnMappingSuggester.key(fileHeaders.get(i)), i);   // first duplicate wins
        }
        Map<CanonicalField, Integer> resolved = new EnumMap<>(CanonicalField.class);
        List<String> missing = new ArrayList<>();
        profile.getColumnMappings().forEach((wire, header) -> {
            Integer idx = indexByKey.get(ColumnMappingSuggester.key(header));
            Optional<CanonicalField> field = CanonicalField.fromWire(wire);
            if (idx == null) missing.add(header);
            else field.ifPresent(f -> resolved.put(f, idx));
        });
        return new ResolvedMapping(profile.getId(), profile.getVersion(), profile.getPreambleRows(), resolved, missing);
    }

    // ------------------------------------------------------------------ validation

    private Map<String, String> validate(ReportType type, Map<String, String> mappings) {
        Map<String, String> clean = new LinkedHashMap<>();
        Set<String> usedHeaders = new HashSet<>();
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            CanonicalField field = CanonicalField.fromWire(e.getKey()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown canonical field: " + e.getKey()));
            if (!field.appliesTo(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Field '" + field.wire() + "' does not apply to " + type + " reports");
            }
            String source = e.getValue() == null ? "" : e.getValue().trim();
            if (source.isEmpty()) continue;                                   // explicitly unmapped
            if (!usedHeaders.add(ColumnMappingSuggester.key(source))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Column '" + source + "' is mapped to more than one field");
            }
            clean.put(field.wire(), source);
        }
        List<String> missing = CanonicalField.forType(type).stream()
                .filter(f -> f.required() && !clean.containsKey(f.wire()))
                .map(CanonicalField::wire).toList();
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required fields not mapped: " + missing);
        }
        return clean;
    }
}
