import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

public class PropBacktestSetup {
    private static final String DB_URL = "jdbc:sqlite:nba_secret_weapon.db";

    public static void main(String[] args) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS prop_predictions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "created_at TEXT DEFAULT CURRENT_TIMESTAMP, " +
                    "player_name TEXT, " +
                    "player_id TEXT, " +
                    "team_abbr TEXT, " +
                    "matchup TEXT, " +
                    "market TEXT, " +
                    "prop_line REAL, " +
                    "bookie_odds REAL, " +
                    "implied_probability REAL, " +
                    "fair_bookie_probability REAL, " +
                    "prophet_prob REAL, " +
                    "edge REAL, " +
                    "is_value INTEGER, " +
                    "l5_average REAL, " +
                    "season_average REAL, " +
                    "projected_stat REAL, " +
                    "data_quality TEXT, " +
                    "status TEXT DEFAULT 'PENDING', " +
                    "actual_stat REAL, " +
                    "result TEXT, " +
                    "profit REAL, " +
                    "evaluated_at TEXT" +
                    ")");

            Set<String> cols = getColumns(conn, "prop_predictions");
            addColumnIfMissing(stmt, cols, "created_at", "TEXT DEFAULT CURRENT_TIMESTAMP");
            addColumnIfMissing(stmt, cols, "player_name", "TEXT");
            addColumnIfMissing(stmt, cols, "player_id", "TEXT");
            addColumnIfMissing(stmt, cols, "team_abbr", "TEXT");
            addColumnIfMissing(stmt, cols, "matchup", "TEXT");
            addColumnIfMissing(stmt, cols, "market", "TEXT");
            addColumnIfMissing(stmt, cols, "prop_line", "REAL");
            addColumnIfMissing(stmt, cols, "bookie_odds", "REAL");
            addColumnIfMissing(stmt, cols, "implied_probability", "REAL");
            addColumnIfMissing(stmt, cols, "fair_bookie_probability", "REAL");
            addColumnIfMissing(stmt, cols, "prophet_prob", "REAL");
            addColumnIfMissing(stmt, cols, "edge", "REAL");
            addColumnIfMissing(stmt, cols, "is_value", "INTEGER");
            addColumnIfMissing(stmt, cols, "l5_average", "REAL");
            addColumnIfMissing(stmt, cols, "season_average", "REAL");
            addColumnIfMissing(stmt, cols, "projected_stat", "REAL");
            addColumnIfMissing(stmt, cols, "data_quality", "TEXT");
            addColumnIfMissing(stmt, cols, "status", "TEXT DEFAULT 'PENDING'");
            addColumnIfMissing(stmt, cols, "actual_stat", "REAL");
            addColumnIfMissing(stmt, cols, "result", "TEXT");
            addColumnIfMissing(stmt, cols, "profit", "REAL");
            addColumnIfMissing(stmt, cols, "evaluated_at", "TEXT");

            System.out.println("✅ prop_predictions ist bereit für Backtesting.");
            System.out.println("ℹ️ Tipp: Falls dein PlayerPropsPuller camelCase-Spalten nutzt, ist das okay. PropResultPuller kann beides lesen.");

        } catch (Exception e) {
            System.err.println("❌ Fehler beim Backtest-Setup:");
            e.printStackTrace();
        }
    }

    private static Set<String> getColumns(Connection conn, String tableName) throws Exception {
        Set<String> cols = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name").toLowerCase());
            }
        }
        return cols;
    }

    private static void addColumnIfMissing(Statement stmt, Set<String> cols, String columnName, String sqlType) throws Exception {
        if (!cols.contains(columnName.toLowerCase())) {
            stmt.execute("ALTER TABLE prop_predictions ADD COLUMN " + columnName + " " + sqlType);
            cols.add(columnName.toLowerCase());
            System.out.println("➕ Spalte ergänzt: " + columnName);
        }
    }
}
