package ingest;

import enums.ReportType;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Our canonical column names plus the header aliases we recognise for each. Add aliases as new partners appear. */
public enum CanonicalField {

    ORDER_ID("order_id", EnumSet.allOf(ReportType.class), true, ValueType.ANY,
            "order id", "order no", "order number", "ord id", "ord no", "order ref", "order reference",
            "order", "customer order id", "reference no"),

    // ---- settlement reports
    CHANNEL("channel", EnumSet.of(ReportType.SETTLEMENT), false, ValueType.TEXT,
            "channel", "marketplace", "sales channel", "platform", "store", "marketplace name", "portal"),
    ITEM_PRICE("item_price", EnumSet.of(ReportType.SETTLEMENT), false, ValueType.NUMERIC,
            "item price", "selling price", "sale amount", "product price", "item value", "invoice amount",
            "order item value", "price", "sale value", "item amount", "seller price"),
    MARKETPLACE_FEE("marketplace_fee", EnumSet.of(ReportType.SETTLEMENT), false, ValueType.NUMERIC,
            "marketplace fee", "commission", "commission fee", "platform fee", "total marketplace fee",
            "seller fee", "referral fee", "commission amount", "total fees", "fees", "marketplace commission"),
    GST_TCS("gst_tcs", EnumSet.of(ReportType.SETTLEMENT), false, ValueType.NUMERIC,
            "gst tcs", "tcs", "tcs gst", "gst tax collected at source", "tax collected at source",
            "tcs amount", "gst tcs amount"),
    INCOME_TAX_TDS("income_tax_tds", EnumSet.of(ReportType.SETTLEMENT), false, ValueType.NUMERIC,
            "income tax tds", "tds", "tds amount", "tax deducted at source", "its tds", "income tax", "tds deducted"),
    NET_PAYOUT("net_payout", EnumSet.of(ReportType.SETTLEMENT), true, ValueType.NUMERIC,
            "net payout", "net settlement", "settlement amount", "payout amount", "net amount", "payout",
            "settled amount", "net payable", "amount settled", "bank settlement value", "settlement value",
            "net remittance", "total payout"),
    SETTLEMENT_DATE("settlement_date", EnumSet.of(ReportType.SETTLEMENT), false, ValueType.DATE,
            "settlement date", "payment date", "payout date", "date settled", "settled on", "remittance date",
            "settle date", "payment settled date"),

    // ---- freight reports
    COURIER_NAME("courier_name", EnumSet.of(ReportType.FREIGHT), false, ValueType.TEXT,
            "courier name", "courier", "carrier", "carrier name", "logistics partner", "shipping partner",
            "transporter", "courier partner", "logistics", "lsp"),
    CHARGED_WEIGHT("charged_weight", EnumSet.of(ReportType.FREIGHT), true, ValueType.NUMERIC,
            "charged weight", "billed weight", "chargeable weight", "weight charged", "billable weight",
            "weight", "charged wt", "chrg wt"),
    APPLIED_WEIGHT_SLAB("applied_weight_slab", EnumSet.of(ReportType.FREIGHT), false, ValueType.ANY,
            "applied weight slab", "weight slab", "slab", "weight bracket", "slab applied", "weight category",
            "applied slab"),
    BASE_CHARGE("base_charge", EnumSet.of(ReportType.FREIGHT), true, ValueType.NUMERIC,
            "base charge", "freight charge", "freight charges", "shipping charge", "shipping charges",
            "base freight", "base rate", "freight", "courier charge", "forward charge", "total freight",
            "charged amount", "freight amount"),
    INVOICE_DATE("invoice_date", EnumSet.of(ReportType.FREIGHT), false, ValueType.DATE,
            "invoice date", "bill date", "billing date", "invoice dt", "date of invoice");

    public enum ValueType { ANY, TEXT, NUMERIC, DATE }

    private final String wire;
    private final Set<ReportType> types;
    private final boolean required;
    private final ValueType expected;
    private final List<String> aliases;

    CanonicalField(String wire, Set<ReportType> types, boolean required, ValueType expected, String... aliases) {
        this.wire = wire;
        this.types = types;
        this.required = required;
        this.expected = expected;
        this.aliases = List.of(aliases);
    }

    public String wire() { return wire; }
    public boolean required() { return required; }
    public ValueType expected() { return expected; }
    public List<String> aliases() { return aliases; }
    public boolean appliesTo(ReportType t) { return types.contains(t); }

    public static List<CanonicalField> forType(ReportType t) {
        return Arrays.stream(values()).filter(f -> f.appliesTo(t)).toList();
    }

    public static Optional<CanonicalField> fromWire(String s) {
        return Arrays.stream(values()).filter(f -> f.wire.equals(s)).findFirst();
    }
}
