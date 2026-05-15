import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;

public class DashboardUpdater {
    private static final String DB_URL = "jdbc:sqlite:nba_secret_weapon.db";
    private static final String CONFIG_PATH = "config.properties";
    private static final String GAMES_JSON_PATH = "games.json";
    private static final String STATS_JSON_PATH = "stats.json";

    private static String discordWebhookUrl = "";

    public static void main(String[] args) {
        try {
            Properties config = loadConfig();
            discordWebhookUrl = optionalConfig(config, "DISCORD_WEBHOOK_URL");

            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                System.out.println("🚀 Starte Dashboard-Update & JSON-Export...");

                JSONObject statsJson = buildStatsJson(conn);
                JSONArray gamesJson = buildGamesJson(conn);

                Files.write(Paths.get(GAMES_JSON_PATH), gamesJson.toString(4).getBytes(StandardCharsets.UTF_8));
                Files.write(Paths.get(STATS_JSON_PATH), statsJson.toString(4).getBytes(StandardCharsets.UTF_8));

                System.out.println("✅ games.json geschrieben: " + gamesJson.length() + " Spiele");
                System.out.println("✅ stats.json geschrieben");
            }
        } catch (Exception e) {
            System.err.println("❌ Fehler im DashboardUpdater:");
            e.printStackTrace();
        }
    }

    private static JSONObject buildStatsJson(Connection conn) throws Exception {
        int totalGames = 0;
        int wonGames = 0;
        double winRate = 0.0;

        try (Statement statStmt = conn.createStatement()) {
            ResultSet rsStats = statStmt.executeQuery(
                    "SELECT COUNT(*) AS total, COALESCE(SUM(is_correct), 0) AS won "
                            + "FROM prediction_history WHERE actual_winner IS NOT NULL"
            );
            if (rsStats.next()) {
                totalGames = rsStats.getInt("total");
                wonGames = rsStats.getInt("won");
                if (totalGames > 0) winRate = ((double) wonGames / totalGames) * 100.0;
            }
        }

        JSONObject statsJson = new JSONObject();
        statsJson.put("total", totalGames);
        statsJson.put("won", wonGames);
        statsJson.put("rate", round(winRate, 1));
        statsJson.put("rateLabel", String.format(Locale.US, "%.1f%%", winRate));
        return statsJson;
    }

    private static JSONArray buildGamesJson(Connection conn) throws Exception {
        String sql = "SELECT g.game_id, g.home_team, g.away_team, g.game_date, g.status, "
                + "o.home_ml, o.away_ml "
                + "FROM games g "
                + "JOIN odds o ON o.game_id = g.game_id "
                + "WHERE o.id = ("
                + "  SELECT o2.id FROM odds o2 "
                + "  WHERE o2.game_id = g.game_id "
                + "  ORDER BY o2.timestamp DESC, o2.id DESC LIMIT 1"
                + ") "
                + "ORDER BY g.game_date ASC LIMIT 12";

        JSONArray gamesJson = new JSONArray();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                JSONObject g = new JSONObject();
                String gameId = rs.getString("game_id");
                String homeName = rs.getString("home_team");
                String awayName = rs.getString("away_team");

                ZonedDateTime dt = ZonedDateTime.parse(rs.getString("game_date"));
                String timeLabel = dt.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.GERMAN)
                        + ". " + String.format("%02d:%02d", dt.getHour(), dt.getMinute()) + " Uhr";

                double homeMl = rs.getDouble("home_ml");
                double awayMl = rs.getDouble("away_ml");
                double bookieProb = safeImplied(homeMl);

                int homePower = getPowerIndex(conn, homeName);
                int awayPower = getPowerIndex(conn, awayName);
                double powerDiff = (homePower - awayPower) / 5.0;
                double finalProb = clamp(bookieProb + powerDiff, 1.0, 99.0);
                double edge = finalProb - bookieProb;
                boolean isValue = edge >= 5.0;

                g.put("gameId", gameId);
                g.put("time", timeLabel);
                g.put("status", rs.getString("status"));
                g.put("home", new JSONObject().put("name", homeName).put("ml", round(homeMl, 2)));
                g.put("away", new JSONObject().put("name", awayName).put("ml", round(awayMl, 2)));
                g.put("bookieProb", round(bookieProb, 1));
                g.put("prophetProb", round(finalProb, 1));
                g.put("propProb", String.format(Locale.US, "%.0f%%", finalProb));
                g.put("edge", round(edge, 1));
                g.put("valueBet", isValue);
                g.put("prop", String.format(Locale.US, "Buchmacher: %.1f%% | Prophet Engine: %.1f%%", bookieProb, finalProb));
                gamesJson.put(g);

                saveGamePrediction(conn, gameId, homeName, awayName, finalProb >= 50.0 ? homeName : awayName);
                if (isValue) sendDiscordAlert(homeName, awayName, homeMl, finalProb, edge);
            }
        }
        return gamesJson;
    }

    private static void saveGamePrediction(Connection conn, String gameId, String homeTeam, String awayTeam, String predictedWinner) {
        String sql = "INSERT OR IGNORE INTO prediction_history "
                + "(game_id, home_team, away_team, predicted_winner) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, gameId);
            ps.setString(2, homeTeam);
            ps.setString(3, awayTeam);
            ps.setString(4, predictedWinner);
            ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("⚠️ Prediction-History konnte nicht geschrieben werden: " + e.getMessage());
        }
    }

    private static int getPowerIndex(Connection conn, String teamName) {
        String sql = "SELECT power_index FROM team_power WHERE team_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("power_index");
        } catch (Exception e) {
            System.out.println("⚠️ Kein Power-Index für " + teamName + ": " + e.getMessage());
        }
        return 50;
    }

    private static void sendDiscordAlert(String home, String away, double quote, double chance, double edge) {
        if (discordWebhookUrl == null || discordWebhookUrl.trim().isEmpty()) return;
        try {
            String message = String.format(Locale.US,
                    "🚨 **PROPHET ALARM // HIGH VALUE BET** 🚨\n🏀 **%s** vs **%s**\n💰 Home ML: **%.2f**\n🎯 Prophet Chance: **%.1f%%**\n📈 Edge: **+%.1f%%**",
                    home, away, quote, chance, edge);

            JSONObject payload = new JSONObject();
            payload.put("content", message);

            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(discordWebhookUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                            .build(),
                    HttpResponse.BodyHandlers.discarding()
            );
        } catch (Exception e) {
            System.out.println("⚠️ Discord-Alert fehlgeschlagen: " + e.getMessage());
        }
    }

    private static double safeImplied(double decimalOdds) {
        if (decimalOdds <= 1.0) return 0.0;
        return (1.0 / decimalOdds) * 100.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    private static Properties loadConfig() throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);
        }
        return props;
    }

    private static String optionalConfig(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) value = System.getenv(key);
        return value == null ? "" : value.trim();
    }
}
