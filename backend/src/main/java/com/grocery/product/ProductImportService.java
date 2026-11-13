package com.grocery.product;

import com.grocery.category.*;
import com.grocery.common.Exceptions.*;
import com.grocery.product.ProductDtos.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Bulk import: ALL rows are validated first; if any row fails, NOTHING is persisted
 * and a per-row error report is returned. Otherwise all rows are saved in ONE transaction.
 */
@Service
public class ProductImportService {
    static final List<String> HEADERS = List.of("sku", "name", "barcode", "brand", "category",
        "purchase_price", "selling_price", "current_stock", "min_stock_level", "unit", "expiry_date");
    private static final int MAX_ROWS = 5000;

    private final ProductRepository products; private final CategoryRepository categories;
    private final com.grocery.inventory.InventoryService inventory; private final com.grocery.audit.AuditService audit;
    public ProductImportService(ProductRepository p, CategoryRepository c, com.grocery.inventory.InventoryService inv, com.grocery.audit.AuditService a) {
        products = p; categories = c; inventory = inv; audit = a;
    }

    public byte[] template() {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Products");
            Row h = s.createRow(0);
            CellStyle bold = wb.createCellStyle(); Font f = wb.createFont(); f.setBold(true); bold.setFont(f);
            for (int i = 0; i < HEADERS.size(); i++) { Cell c = h.createCell(i); c.setCellValue(HEADERS.get(i)); c.setCellStyle(bold); s.setColumnWidth(i, 18 * 256); }
            String[] sample = {"RICE-BAS-5KG", "Basmati Rice 5kg", "8901234567890", "India Gate", "Staples", "420.00", "475.00", "40", "10", "pkts", "2027-06-30"};
            Row r = s.createRow(1);
            for (int i = 0; i < sample.length; i++) r.createCell(i).setCellValue(sample[i]);
            Sheet help = wb.createSheet("Instructions");
            String[] lines = {"Required: sku, name, purchase_price, selling_price, unit.",
                "unit must be one of: kg, g, L, ml, pcs, pkts, boxes.", "expiry_date format: yyyy-MM-dd (optional).",
                "Unknown categories are created automatically.", "Delete the sample row before uploading.",
                "If any row has an error, nothing is imported."};
            for (int i = 0; i < lines.length; i++) help.createRow(i).createCell(0).setCellValue(lines[i]);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Transactional
    public ImportResult importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("File is empty");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase();
        List<Map<String, String>> rows;
        try (InputStream in = file.getInputStream()) {
            if (name.endsWith(".csv")) rows = readCsv(in);
            else if (name.endsWith(".xlsx")) rows = readXlsx(in);
            else throw new BusinessException("Only .xlsx or .csv files are supported");
        } catch (IOException e) { throw new BusinessException("Could not read file: " + e.getMessage()); }
        if (rows.isEmpty()) throw new BusinessException("No data rows found");
        if (rows.size() > MAX_ROWS) throw new BusinessException("Too many rows (max " + MAX_ROWS + ")");

        // Pass 1: validate everything without touching the DB
        List<RowError> errors = new ArrayList<>();
        List<Product> parsed = new ArrayList<>();
        List<String> parsedCategoryNames = new ArrayList<>();
        List<Integer> parsedRows = new ArrayList<>();
        List<BigDecimal> openingStocks = new ArrayList<>();
        Set<String> seenSkus = new HashSet<>(), seenCodes = new HashSet<>();

        for (Map<String, String> row : rows) {
            int n = Integer.parseInt(row.get("_row"));
            String sku = row.getOrDefault("sku", "");
            List<String> errs = new ArrayList<>();
            Product p = new Product();
            if (sku.isBlank()) errs.add("sku is required");
            else if (!seenSkus.add(sku.toLowerCase())) errs.add("duplicate sku in file");
            if (row.getOrDefault("name", "").isBlank()) errs.add("name is required");
            p.setSku(sku); p.setName(row.getOrDefault("name", ""));
            p.setBrand(ProductService.blankToNull(row.get("brand")));
            String bc = ProductService.blankToNull(row.get("barcode"));
            if (bc != null && !seenCodes.add(bc)) errs.add("duplicate barcode in file");
            p.setBarcode(bc);
            p.setPurchasePrice(num(row, "purchase_price", true, errs));
            p.setSellingPrice(num(row, "selling_price", true, errs));
            BigDecimal opening = Optional.ofNullable(num(row, "current_stock", false, errs)).orElse(BigDecimal.ZERO);
            p.setCurrentStock(BigDecimal.ZERO); // opening stock is applied through the inventory engine (logged + locked)
            p.setTaxRate(BigDecimal.ZERO);
            p.setMinStockLevel(Optional.ofNullable(num(row, "min_stock_level", false, errs)).orElse(BigDecimal.ZERO));
            try { p.setUnit(Unit.valueOf(row.getOrDefault("unit", "").trim().toUpperCase())); }
            catch (Exception e) { errs.add("unit must be one of kg, g, L, ml, pcs, pkts, boxes"); }
            String exp = row.getOrDefault("expiry_date", "");
            if (!exp.isBlank()) try { p.setExpiryDate(LocalDate.parse(exp.trim())); } catch (Exception e) { errs.add("expiry_date must be yyyy-MM-dd"); }
            if (errs.isEmpty()) { parsed.add(p); parsedRows.add(n); openingStocks.add(opening); parsedCategoryNames.add(row.getOrDefault("category", "").trim()); }
            else errors.add(new RowError(n, sku, String.join("; ", errs)));
        }

        // DB-level duplicate checks (batched)
        if (!parsed.isEmpty()) {
            Set<String> existing = new HashSet<>(products.findExistingSkus(seenSkus));
            Set<String> existingCodes = seenCodes.isEmpty() ? Set.of() : new HashSet<>(products.findExistingBarcodes(seenCodes));
            for (int i = 0; i < parsed.size(); i++) {
                Product p = parsed.get(i);
                if (existing.contains(p.getSku().toLowerCase())) errors.add(new RowError(parsedRows.get(i), p.getSku(), "sku already exists in database"));
                else if (p.getBarcode() != null && existingCodes.contains(p.getBarcode())) errors.add(new RowError(parsedRows.get(i), p.getSku(), "barcode already exists in database"));
            }
        }

        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparingInt(RowError::row));
            return new ImportResult(false, rows.size(), 0, errors); // nothing written yet
        }

        // Pass 2: persist (categories + products) in this single transaction
        Map<String, Category> catCache = new HashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            String cat = parsedCategoryNames.get(i);
            if (!cat.isBlank()) parsed.get(i).setCategory(catCache.computeIfAbsent(cat.toLowerCase(), k ->
                categories.findByNameIgnoreCase(cat).orElseGet(() -> categories.save(new Category(cat, null)))));
        }
        products.saveAll(parsed);
        for (int i = 0; i < parsed.size(); i++)
            if (openingStocks.get(i).signum() > 0)
                inventory.apply(parsed.get(i).getId(), com.grocery.inventory.MovementType.MANUAL_ADD, openingStocks.get(i), "IMPORT", null, "Opening stock (bulk import)");
        audit.log("BULK_IMPORT", "PRODUCT", null, null, java.util.Map.of("rows", rows.size(), "imported", parsed.size()));
        return new ImportResult(true, rows.size(), parsed.size(), List.of());
    }

    private static BigDecimal num(Map<String, String> row, String col, boolean required, List<String> errs) {
        String v = row.getOrDefault(col, "").trim();
        if (v.isEmpty()) { if (required) errs.add(col + " is required"); return null; }
        try {
            BigDecimal d = new BigDecimal(v.replace(",", ""));
            if (d.signum() < 0) { errs.add(col + " cannot be negative"); return null; }
            return d;
        } catch (NumberFormatException e) { errs.add(col + " is not a number"); return null; }
    }

    private List<Map<String, String>> readCsv(InputStream in) throws IOException {
        var fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).build();
        List<Map<String, String>> out = new ArrayList<>();
        try (var parser = CSVParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8), fmt)) {
            List<String> raw = parser.getHeaderNames();
            List<String> norm = raw.stream().map(h -> h.replace("\uFEFF", "").trim().toLowerCase()).toList();
            checkHeaders(norm);
            for (CSVRecord rec : parser) {
                Map<String, String> m = new HashMap<>();
                for (int i = 0; i < raw.size(); i++) m.put(norm.get(i), rec.get(raw.get(i)) == null ? "" : rec.get(raw.get(i)));
                m.put("_row", String.valueOf(rec.getRecordNumber() + 1));
                out.add(m);
            }
        }
        return out;
    }

    private List<Map<String, String>> readXlsx(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet s = wb.getSheetAt(0);
            Row h = s.getRow(0);
            if (h == null) throw new BusinessException("Header row missing");
            List<String> heads = new ArrayList<>();
            for (int c = 0; c < h.getLastCellNum(); c++) heads.add(cellStr(h.getCell(c)).trim().toLowerCase());
            checkHeaders(heads);
            List<Map<String, String>> out = new ArrayList<>();
            for (int i = 1; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                if (r == null) continue;
                Map<String, String> m = new HashMap<>(); boolean any = false;
                for (int c = 0; c < heads.size(); c++) {
                    String v = cellStr(r.getCell(c)).trim();
                    if (!v.isEmpty()) any = true;
                    m.put(heads.get(c), v);
                }
                if (!any) continue;
                m.put("_row", String.valueOf(i + 1));
                out.add(m);
            }
            return out;
        } catch (org.apache.poi.EmptyFileException | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw new BusinessException("Not a valid .xlsx file");
        }
    }

    private static void checkHeaders(List<String> heads) {
        List<String> missing = new ArrayList<>();
        for (String req : List.of("sku", "name", "purchase_price", "selling_price", "unit")) if (!heads.contains(req)) missing.add(req);
        if (!missing.isEmpty()) throw new BusinessException("Missing required columns: " + String.join(", ", missing));
    }

    private static String cellStr(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue().toLocalDate().toString()
                : BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> new DataFormatter().formatCellValue(c);
            default -> "";
        };
    }
}
