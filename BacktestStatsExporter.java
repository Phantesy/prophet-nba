import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class BacktestStatsExporter {
    private static final String DB_URL = "jdbc:sqlite:nba_secret_weapon.db";
    private static final String OUTPUT_PATH = "performance.json";

    public static void main(String[] args) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            Set<String> cols = getColumns(conn, "prop_predictions");
            if (cols.isEmpty()) {
                System.out.println("❌ Tabelle prop_predictions fehlt. Bitte zuerst PropBacktestSetup ausführen.");
                return;
            }

            String resultCol = choose(cols, "result", "result");
            String profitCol = choose(cols, "profit", "profit");
            String marketCol = choose(cols, "market", "market", "prop_type", "propType");
            String qualityCol = choose(cols, "data_quality", "data_quality", "dataQuality");
            String edgeCol = choose(cols, "edge", "edge");
            String isValueCol = choose(cols, "is_value", "is_value", "isValue");

            Aggregate overall = new Aggregate();
            Map<String, Aggregate> byMarket = new HashMap<>();
            Map<String, Aggregate> byQuality = new HashMap<>();
            Aggregate valueOnly = new Aggregate();

            String sql = "SELECT * FROM prop_predictions WHERE " + q(resultCol) + " IS NOT NULL AND " + q(resultCol) + " != ''";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String result = safeString(rs, resultCol, "").toUpperCase(Locale.ROOT);
                    double profit = safeDouble(rs, profitCol, 0.0);
                    String market = normalizeGroup(safeString(rs, marketCol, "UNKNOWN"));
                    String quality = normalizeGroup(safeString(rs, qualityCol, "UNKNOWN"));
                    double edge = safeDouble(rs, edgeCol, 0.0);
                    boolean isValue = safeBoolean(rs, isValueCol) || edge >= 5.0;

                    overall.add(result, profit);
                    byMarket.computeIfAbsent(market, k -> new Aggregate()).add(result, profit);
                    byQuality.computeIfAbsent(quality, k -> new Aggregate()).add(result, profit);
                    if (isValue) valueOnly.add(result, profit);
                }
            }

            JSONObject out = new JSONObject();
            out.put("generatedAt", java.time.Instant.now().toString());
            out.put("overall", overall.toJson());
            out.put("valueOnly", valueOnly.toJson());
            out.put("byMarket", mapToArray(byMarket));
            out.put("byDataQuality", mapToArray(byQuality));

            Files.write(Paths.get(OUTPUT_PATH), out.toString(4).getBytes());
            System.out.println("✅ Backtest Performance exportiert: " + OUTPUT_PATH);
            System.out.println("📊 Overall ROI: " + overall.roiString() + " | Winrate: " + overall.winRateString() + " | Picks: " + overall.total);
        } catch (Exception e) {
            System.err.println("❌ Fehler im BacktestStatsExporter:");
            e.printStackTrace();
        }
    }

    private static JSONArray mapToArray(Map<String, Aggregate> map) {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, Aggregate> entry : map.entrySet()) {
            JSONObject obj = entry.getValue().toJson();
            obj.put("group", entry.getKey());
            arr.put(obj);
        }
        return arr;
    }

    private static Set<String> getColumns(Connection conn, String tableName) throws Exception {
        Set<String> cols = new HashSet<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) cols.add(rs.getString("name").toLowerCase());
        }
        return cols;
    }

    private static String choose(Set<String> cols, String fallback, String... names) {
        for (String n : names) {
            if (cols.contains(n.toLowerCase())) return n;
        }
        return fallback;
    }

    private static String q(String col) {
        return "\"" + col.replace("\"", "\"\"") + "\"";
    }

    private static String safeString(ResultSet rs, String col, String def) {
        try {
            String value = rs.getString(col);
            return value == null || value.trim().isEmpty() ? def : value;
        } catch (Exception e) {
            return def;
        }
    }

    private static double safeDouble(ResultSet rs, String col, double def) {
        try {
            double value = rs.getDouble(col);
            return rs.wasNull() ? def : value;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean safeBoolean(ResultSet rs, String col) {
        try {
            Object obj = rs.getObject(col);
            if (obj == null) return false;
            if (obj instanceof Number) return ((Number)obj).intValue() != 0;
            String s = obj.toString().trim().toLowerCase(Locale.ROOT);
            return s.equals("true") || s.equals("1") || s.equals("yes");
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeGroup(String value) {
        if (value == null || value.trim().isEmpty()) return "UNKNOWN";
        return value.toUpperCase(Locale.ROOT).replace("PLAYER_", "").trim();
    }

    private static class Aggregate {
        int total = 0;
        int wins = 0;
        int losses = 0;
        int pushes = 0;
        double profit = 0.0;

        void add(String result, double p) {
            total++;
            if ("WIN".equals(result)) wins++;
            else if ("LOSS".equals(result)) losses++;
            else if ("PUSH".equals(result)) pushes++;
            profit += p;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("total", total);
            o.put("wins", wins);
            o.put("losses", losses);
            o.put("pushes", pushes);
            o.put("profitUnits", round(profit));
            o.put("winRate", winRateString());
            o.put("roi", roiString());
            return o;
        }

        String winRateString() {
            int graded = wins + losses;
            if (graded == 0) return "0.0%";
            return String.format(Locale.US, "%.1f%%", (wins * 100.0 / graded));
        }

        String roiString() {
            int staked = wins + losses;
            if (staked == 0) return "0.0%";
            return String.format(Locale.US, "%.1f%%", (profit * 100.0 / staked));
        }

        double round(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
