import java.io.FileInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;

public class NBADataPuller {
    private static final String DB_URL = "jdbc:sqlite:nba_secret_weapon.db";
    private static final String CONFIG_PATH = "config.properties";

    public static void main(String[] args) {
        try {
            Properties config = loadConfig();
            String apiKey = requireConfig(config, "ODDS_API_KEY");
            String region = config.getProperty("ODDS_REGION", "us");
            String bookmakerKey = config.getProperty("BOOKMAKER_KEY", "draftkings");

            String apiUrl = "https://api.the-odds-api.com/v4/sports/basketball_nba/odds/"
                    + "?apiKey=" + encode(apiKey)
                    + "&regions=" + encode(region)
                    + "&markets=h2h"
                    + "&oddsFormat=decimal";

            System.out.println("⏳ Verbinde mit The Odds API...");
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = get(client, apiUrl);

            if (response.statusCode() != 200) {
                System.out.println("❌ API Fehler (" + response.statusCode() + "): " + response.body());
                return;
            }

            JSONArray gamesArray = new JSONArray(response.body());
            int savedGames = 0;
            int savedOdds = 0;

            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                String insertGame = "INSERT OR IGNORE INTO games "
                        + "(game_id, home_team, away_team, game_date, status) "
                        + "VALUES (?, ?, ?, ?, 'Upcoming')";
                String insertOdds = "INSERT INTO odds (game_id, home_ml, away_ml) VALUES (?, ?, ?)";

                for (int i = 0; i < gamesArray.length(); i++) {
                    JSONObject game = gamesArray.getJSONObject(i);
                    String gameId = game.getString("id");
                    String homeTeam = game.getString("home_team");
                    String awayTeam = game.getString("away_team");
                    String gameDate = game.getString("commence_time");

                    try (PreparedStatement pstmtGame = conn.prepareStatement(insertGame)) {
                        pstmtGame.setString(1, gameId);
                        pstmtGame.setString(2, homeTeam);
                        pstmtGame.setString(3, awayTeam);
                        pstmtGame.setString(4, gameDate);
                        int affected = pstmtGame.executeUpdate();
                        if (affected > 0) savedGames++;
                    }

                    JSONObject selectedBookmaker = selectBookmaker(game.optJSONArray("bookmakers"), bookmakerKey);
                    if (selectedBookmaker == null) {
                        System.out.println("⚠️ Keine Quoten gefunden: " + homeTeam + " vs " + awayTeam);
                        continue;
                    }

                    double homeMl = 0.0;
                    double awayMl = 0.0;
                    JSONArray markets = selectedBookmaker.optJSONArray("markets");
                    if (markets == null || markets.length() == 0) continue;

                    JSONArray outcomes = markets.getJSONObject(0).optJSONArray("outcomes");
                    if (outcomes == null) continue;

                    for (int k = 0; k < outcomes.length(); k++) {
                        JSONObject outcome = outcomes.getJSONObject(k);
                        String name = outcome.optString("name", "");
                        if (name.equals(homeTeam)) homeMl = outcome.optDouble("price", 0.0);
                        if (name.equals(awayTeam)) awayMl = outcome.optDouble("price", 0.0);
                    }

                    if (homeMl > 0.0 && awayMl > 0.0) {
                        try (PreparedStatement pstmtOdds = conn.prepareStatement(insertOdds)) {
                            pstmtOdds.setString(1, gameId);
                            pstmtOdds.setDouble(2, homeMl);
                            pstmtOdds.setDouble(3, awayMl);
                            pstmtOdds.executeUpdate();
                            savedOdds++;
                        }
                        System.out.printf("🏀 Gespeichert: %s vs %s | %s ML: %.2f / %.2f%n",
                                homeTeam, awayTeam, selectedBookmaker.optString("key", "bookmaker"), homeMl, awayMl);
                    } else {
                        System.out.println("⚠️ Unvollständige Moneyline: " + homeTeam + " vs " + awayTeam);
                    }
                }
            }

            System.out.println("\n✅ Fertig: " + savedGames + " neue Spiele, " + savedOdds + " Odds-Snapshots gespeichert.");
        } catch (Exception e) {
            System.out.println("❌ Schwerer Fehler: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static JSONObject selectBookmaker(JSONArray bookmakers, String preferredKey) {
        if (bookmakers == null || bookmakers.length() == 0) return null;
        for (int i = 0; i < bookmakers.length(); i++) {
            JSONObject bookie = bookmakers.getJSONObject(i);
            if (preferredKey.equalsIgnoreCase(bookie.optString("key", ""))) {
                return bookie;
            }
        }
        return bookmakers.getJSONObject(0);
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Properties loadConfig() throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);
        }
        return props;
    }

    private static String requireConfig(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(key);
        }
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Fehlender Config-Wert: " + key);
        }
        return value.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
