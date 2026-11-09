package dashboard;

/** Whitelist of sortable columns: the API never passes client text straight into a Sort. */
public enum ClaimSortField {
    CREATED_AT("createdAt"), AMOUNT("discrepancyAmount"), STATUS("status"), PARTNER("partnerName");

    private final String property;

    ClaimSortField(String property) { this.property = property; }

    public String property() { return property; }
}
