import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DatabaseSetup {
    public static void main(String[] args) {
        String url = "jdbc:sqlite:nba_secret_weapon.db";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            System.out.println("🔧 Verbindung zur Datenbank hergestellt.");

            stmt.execute("CREATE TABLE IF NOT EXISTS games (" +
                    "game_id TEXT PRIMARY KEY, " +
                    "home_team TEXT, " +
                    "away_team TEXT, " +
                    "game_date TEXT, " +
                    "status TEXT)"
            );

            stmt.execute("CREATE TABLE IF NOT EXISTS odds (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "game_id TEXT, " +
                    "home_ml REAL, " +
                    "away_ml REAL, " +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (game_id) REFERENCES games (game_id))"
            );

            stmt.execute("CREATE TABLE IF NOT EXISTS player_props (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "game_id TEXT, " +
                    "player_name TEXT, " +
                    "prop_type TEXT, " +
                    "line REAL, " +
                    "over_odds REAL, " +
                    "under_odds REAL, " +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (game_id) REFERENCES games (game_id))"
            );

            stmt.execute("CREATE TABLE IF NOT EXISTS prediction_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "game_id TEXT UNIQUE, " +
                    "home_team TEXT, " +
                    "away_team TEXT, " +
                    "predicted_winner TEXT, " +
                    "actual_winner TEXT, " +
                    "is_correct INTEGER, " +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)"
            );

            stmt.execute("CREATE TABLE IF NOT EXISTS team_power (" +
                    "team_name TEXT PRIMARY KEY, " +
                    "power_index INTEGER, " +
                    "streak TEXT)"
            );

            stmt.execute("CREATE TABLE IF NOT EXISTS players (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "player_name TEXT UNIQUE, " +
                    "espn_id TEXT, " +
                    "team_abbr TEXT, " +
                    "team_name TEXT, " +
                    "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)"
            );

            stmt.execute("CREATE TABLE IF NOT EXISTS prop_predictions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                    "event_id TEXT, " +
                    "player_name TEXT, " +
                    "player_id TEXT, " +
                    "team_abbr TEXT, " +
                    "matchup TEXT, " +
                    "market TEXT, " +
                    "prop_line REAL, " +
                    "bookie_odds REAL, " +
                    "implied_probability REAL, " +
                    "fair_bookie_probability REAL, " +
                    "projected_stat REAL, " +
                    "prophet_prob REAL, " +
                    "edge REAL, " +
                    "is_value INTEGER, " +
                    "l5_average REAL, " +
                    "season_average REAL, " +
                    "data_quality TEXT, " +
                    "result TEXT, " +
                    "result_value REAL, " +
                    "is_correct INTEGER)"
            );

            System.out.println("✅ Datenbank bereit: games, odds, player_props, prediction_history, team_power, players, prop_predictions.");
        } catch (Exception e) {
            System.out.println("❌ Fehler: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
