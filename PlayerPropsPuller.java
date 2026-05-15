import java.io.FileInputStream;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;

public class PlayerPropsPuller {
    private static final String DB_URL = "jdbc:sqlite:nba_secret_weapon.db";
    private static final String CONFIG_PATH = "config.properties";
    private static final String JSON_OUTPUT_PATH = "props.json";

    private static String apiKey;
    private static String discordWebhookUrl;
    private static String bookmakerKey;
    private static String region;
    private static double bookieMarginPercent;
    private static double minValueEdge;
    private static double alertEdgeThreshold;

    enum Market {
        POINTS("player_points", "POINTS", "PTS", new String[]{"points", "point", "pts", "ppg"}),
        REBOUNDS("player_rebounds", "REBOUNDS", "REB", new String[]{"rebounds", "rebound", "reb", "rpg"}),
        ASSISTS("player_assists", "ASSISTS", "AST", new String[]{"assists", "assist", "ast", "apg"});

        final String apiKey;
        final String label;
        final String espnAbbr;
        final String[] aliases;

        Market(String apiKey, String label, String espnAbbr, String[] aliases) {
            this.apiKey = apiKey;
            this.label = label;
            this.espnAbbr = espnAbbr;
            this.aliases = aliases;
        }

        static Market fromApiKey(String key) {
            for (Market market : values()) {
                if (market.apiKey.equalsIgnoreCase(key)) return market;
            }
            return null;
        }
    }

    static class PlayerInfo {
        String espnId;
        String teamAbbr;

        PlayerInfo(String espnId, String teamAbbr) {
            this.espnId = espnId;
            this.teamAbbr = teamAbbr;
        }
    }

    static class StatValue {
        final boolean available;
        final double value;

        private StatValue(boolean available, double value) {
            this.available = available;
            this.value = value;
        }

        static StatValue missing() {
            return new StatValue(false, -1.0);
        }

        static StatValue of(double value) {
            return new StatValue(value >= 0, value);
        }
    }

    public static void main(String[] args) {
        System.out.println("🚀 Prophet Engine: Starte Player Props Pipeline...");

        try {
            Properties config = loadConfig();
            apiKey = requireConfig(config, "ODDS_API_KEY");
            discordWebhookUrl = optionalConfig(config, "DISCORD_WEBHOOK_URL");
            bookmakerKey = config.getProperty("BOOKMAKER_KEY", "draftkings").trim();
            region = config.getProperty("ODDS_REGION", "us").trim();
            bookieMarginPercent = parseDouble(config.getProperty("BOOKIE_MARGIN_PERCENT"), 4.5);
            minValueEdge = parseDouble(config.getProperty("MIN_VALUE_EDGE"), 5.0);
            alertEdgeThreshold = parseDouble(config.getProperty("ALERT_EDGE_THRESHOLD"), 10.0);

            HttpClient client = HttpClient.newHttpClient();
            JSONArray finalPropsArray = new JSONArray();

            String eventsUrl = "https://api.the-odds-api.com/v4/sports/basketball_nba/events?apiKey=" + encode(apiKey);
            HttpResponse<String> eventsResponse = get(client, eventsUrl);
            if (eventsResponse.statusCode() != 200) {
                System.err.println("❌ Events API Fehler (" + eventsResponse.statusCode() + "): " + eventsResponse.body());
                return;
            }

            JSONArray events = new JSONArray(eventsResponse.body());

            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                ensurePropPredictionsTable(conn);

                for (int e = 0; e < events.length(); e++) {
                    JSONObject game = events.getJSONObject(e);
                    String eventId = game.getString("id");
                    String homeTeam = game.getString("home_team");
                    String awayTeam = game.getString("away_team");
                    String matchup = homeTeam + " vs " + awayTeam;

                    System.out.println("\n🏀 Analysiere Props: " + matchup);

                    String propsUrl = "https://api.the-odds-api.com/v4/sports/basketball_nba/events/" + eventId + "/odds"
                            + "?apiKey=" + encode(apiKey)
                            + "&regions=" + encode(region)
                            + "&markets=player_points,player_assists,player_rebounds"
                            + "&oddsFormat=decimal";

                    HttpResponse<String> propsResponse = get(client, propsUrl);
                    if (propsResponse.statusCode() != 200) {
                        System.out.println("⚠️ Props API Fehler für " + matchup + ": " + propsResponse.body());
                        continue;
                    }

                    JSONObject propsData = new JSONObject(propsResponse.body());
                    JSONObject selectedBookmaker = selectBookmaker(propsData.optJSONArray("bookmakers"), bookmakerKey);
                    if (selectedBookmaker == null) {
                        System.out.println("⚠️ Kein Bookmaker mit Props gefunden für " + matchup);
                        continue;
                    }

                    JSONArray markets = selectedBookmaker.optJSONArray("markets");
                    if (markets == null) continue;

                    for (int i = 0; i < markets.length(); i++) {
                        JSONObject marketJson = markets.getJSONObject(i);
                        String marketKey = marketJson.optString("key", "");
                        Market market = Market.fromApiKey(marketKey);
                        if (market == null) continue;

                        JSONArray outcomes = marketJson.optJSONArray("outcomes");
                        if (outcomes == null) continue;

                        for (int j = 0; j < outcomes.length(); j++) {
                            JSONObject outcome = outcomes.getJSONObject(j);
                            if (!"Over".equalsIgnoreCase(outcome.optString("name", ""))) continue;
                            if (!outcome.has("description") || !outcome.has("point") || !outcome.has("price")) continue;

                            String playerName = outcome.getString("description");
                            double line = outcome.getDouble("point");
                            double odds = outcome.getDouble("price");

                            PlayerInfo playerInfo = getPlayerInfoFromDB(conn, playerName, "nba");
                            StatValue seasonAverage = getSeasonAverage(client, playerInfo.espnId, market);
                            StatValue l5Average = getL5Average(client, playerInfo.espnId, market);

                            double projectedStat = projectStat(line, l5Average, seasonAverage);
                            double impliedProbability = safeImplied(odds);
                            double fairBookieProbability = clamp(impliedProbability - bookieMarginPercent, 1.0, 99.0);
                            double prophetProbability = calculateProphetProbability(projectedStat, line, fairBookieProbability);
                            double edge = prophetProbability - fairBookieProbability;
                            boolean isValue = edge >= minValueEdge;
                            String dataQuality = dataQuality(l5Average, seasonAverage);

                            JSONObject propObject = createPropObject(
                                    playerName, playerInfo, matchup, market, line, odds,
                                    impliedProbability, fairBookieProbability, prophetProbability,
                                    edge, isValue, l5Average, seasonAverage, projectedStat, dataQuality
                            );
                            finalPropsArray.put(propObject);
                            savePropPrediction(conn, eventId, propObject);

                            printDebug(playerName, playerInfo.espnId, market, seasonAverage, l5Average,
                                    projectedStat, line, odds, prophetProbability, edge, dataQuality);

                            if (isValue && edge >= alertEdgeThreshold) {
                                sendDiscordAlert(client, propObject);
                            }
                        }
                    }
                    Thread.sleep(250);
                }
            }

            JSONArray sorted = sortPropsArray(finalPropsArray);
            Files.write(Paths.get(JSON_OUTPUT_PATH), sorted.toString(4).getBytes(StandardCharsets.UTF_8));
            System.out.println("\n✅ Pipeline abgeschlossen! " + sorted.length() + " Props analysiert und in props.json exportiert.");
        } catch (Exception e) {
            System.err.println("❌ Fehler im Hauptprozess:");
            e.printStackTrace();
        }
    }

    private static void printDebug(String playerName, String espnId, Market market,
                                   StatValue season, StatValue l5, double projectedStat,
                                   double line, double odds, double prophetProb, double edge, String quality) {
        System.out.printf(Locale.US,
                "DEBUG | %-24s | ESPN:%-8s | %-8s | Season:%6s | L5:%6s | Proj:%5.1f | Line:%5.1f | Odds:%4.2f | Prob:%5.1f%% | Edge:%+5.1f | %s%n",
                playerName,
                espnId == null ? "0" : espnId,
                market.label,
                season.available ? String.format(Locale.US, "%.1f", season.value) : "-",
                l5.available ? String.format(Locale.US, "%.1f", l5.value) : "-",
                projectedStat,
                line,
                odds,
                prophetProb,
                edge,
                quality);
    }

    private static double projectStat(double line, StatValue l5, StatValue season) {
        if (l5.available && season.available) return (l5.value * 0.6) + (season.value * 0.4);
        if (l5.available) return l5.value;
        if (season.available) return season.value;
        return line;
    }

    private static String dataQuality(StatValue l5, StatValue season) {
        if (l5.available && season.available) return "HIGH";
        if (l5.available || season.available) return "MEDIUM";
        return "LOW";
    }

    private static double calculateProphetProbability(double projectedStat, double line, double fairBookieProb) {
        if (line <= 0) return clamp(fairBookieProb, 10.0, 95.0);

        double myProb;
        if (Math.abs(projectedStat - line) < 0.0001) {
            myProb = fairBookieProb;
        } else if (projectedStat > line) {
            double advantage = (projectedStat - line) / line;
            myProb = fairBookieProb + (advantage * 100.0 * 2.0);
        } else {
            double disadvantage = (line - projectedStat) / line;
            myProb = fairBookieProb - (disadvantage * 100.0 * 2.5);
        }
        return round(clamp(myProb, 10.0, 95.0), 1);
    }

    private static StatValue getSeasonAverage(HttpClient client, String espnId, Market market) {
        if (espnId == null || espnId.equals("0") || espnId.trim().isEmpty()) return StatValue.missing();
        try {
            String url = "https://site.api.espn.com/apis/common/v3/sports/basketball/nba/athletes/" + encode(espnId);
            HttpResponse<String> response = get(client, url);
            if (response.statusCode() != 200) {
                System.out.println("⚠️ ESPN Season API Fehler für ID " + espnId + ": " + response.statusCode());
                return StatValue.missing();
            }
            JSONObject root = new JSONObject(response.body());
            Double found = findStatValueRecursive(root, market, 0);
            return found == null ? StatValue.missing() : StatValue.of(found);
        } catch (Exception e) {
            System.out.println("⚠️ Season Average nicht gefunden für ESPN ID " + espnId + ": " + e.getMessage());
            return StatValue.missing();
        }
    }

    private static StatValue getL5Average(HttpClient client, String espnId, Market market) {
        if (espnId == null || espnId.equals("0") || espnId.trim().isEmpty()) return StatValue.missing();
        try {
            String url = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/athletes/" + encode(espnId) + "/gamelog";
            HttpResponse<String> response = get(client, url);
            if (response.statusCode() != 200) {
                System.out.println("⚠️ ESPN Gamelog API Fehler für ID " + espnId + ": " + response.statusCode());
                return StatValue.missing();
            }

            JSONObject root = new JSONObject(response.body());
            List<Double> values = extractGamelogValues(root, market);
            if (values.isEmpty()) return StatValue.missing();

            int count = Math.min(5, values.size());
            double sum = 0.0;
            for (int i = 0; i < count; i++) sum += values.get(i);
            return StatValue.of(round(sum / count, 1));
        } catch (Exception e) {
            System.out.println("⚠️ L5 Average nicht gefunden für ESPN ID " + espnId + ": " + e.getMessage());
            return StatValue.missing();
        }
    }

    private static List<Double> extractGamelogValues(JSONObject root, Market market) {
        List<Double> values = new ArrayList<>();
        if (!root.has("seasonTypes")) return values;

        JSONArray seasonTypes = root.optJSONArray("seasonTypes");
        if (seasonTypes == null) return values;

        for (int s = 0; s < seasonTypes.length() && values.size() < 5; s++) {
            JSONObject seasonType = seasonTypes.optJSONObject(s);
            if (seasonType == null) continue;

            int statIndex = findStatIndex(seasonType, market);
            if (statIndex < 0) continue;

            collectStatsRows(seasonType.opt("events"), statIndex, values);
            collectStatsRows(seasonType.opt("categories"), statIndex, values);
        }
        return values;
    }

    private static int findStatIndex(JSONObject seasonType, Market market) {
        JSONArray categories = seasonType.optJSONArray("categories");
        if (categories == null) return -1;

        for (int c = 0; c < categories.length(); c++) {
            JSONObject category = categories.optJSONObject(c);
            if (category == null) continue;
            JSONArray labels = category.optJSONArray("labels");
            if (labels == null) labels = category.optJSONArray("displayNames");
            if (labels == null) continue;

            for (int i = 0; i < labels.length(); i++) {
                String label = labels.optString(i, "").trim();
                if (matchesMarket(label, market)) return i;
            }
        }
        return -1;
    }

    private static void collectStatsRows(Object node, int statIndex, List<Double> values) {
        if (node == null || values.size() >= 5) return;

        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray stats = obj.optJSONArray("stats");
            if (stats != null && stats.length() > statIndex) {
                Double val = parseStatCell(stats.opt(statIndex));
                if (val != null) values.add(val);
            }
            for (String key : obj.keySet()) {
                if (values.size() >= 5) break;
                collectStatsRows(obj.opt(key), statIndex, values);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length() && values.size() < 5; i++) {
                collectStatsRows(arr.opt(i), statIndex, values);
            }
        }
    }

    private static Double findStatValueRecursive(Object node, Market market, int depth) {
        if (node == null || depth > 8) return null;

        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String descriptor = (obj.optString("name", "") + " "
                    + obj.optString("displayName", "") + " "
                    + obj.optString("shortDisplayName", "") + " "
                    + obj.optString("abbreviation", "") + " "
                    + obj.optString("label", "")).toLowerCase(Locale.ROOT);

            if (matchesMarket(descriptor, market)) {
                Double direct = parseStatCell(obj.opt("value"));
                if (direct != null) return direct;
                direct = parseStatCell(obj.opt("displayValue"));
                if (direct != null) return direct;
            }

            for (String key : obj.keySet()) {
                Double found = findStatValueRecursive(obj.opt(key), market, depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Double found = findStatValueRecursive(arr.opt(i), market, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean matchesMarket(String raw, Market market) {
        if (raw == null) return false;
        String text = raw.toLowerCase(Locale.ROOT).trim();
        if (text.equalsIgnoreCase(market.espnAbbr)) return true;
        for (String alias : market.aliases) {
            String a = alias.toLowerCase(Locale.ROOT);
            if (text.equals(a)) return true;
            if (text.contains(" " + a + " ")) return true;
            if (text.startsWith(a + " ")) return true;
            if (text.endsWith(" " + a)) return true;
            if (text.contains(a)) return true;
        }
        return false;
    }

    private static PlayerInfo getPlayerInfoFromDB(Connection conn, String playerName, String defaultTeam) {
        String cleanedName = cleanPlayerName(playerName);

        String sqlExact = "SELECT espn_id, team_abbr FROM players WHERE player_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlExact)) {
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return new PlayerInfo(rs.getString("espn_id"), rs.getString("team_abbr"));
        } catch (Exception e) {
            System.out.println("⚠️ DB Exact Match Fehler: " + e.getMessage());
        }

        String sqlFuzzy = "SELECT espn_id, team_abbr FROM players "
                + "WHERE REPLACE(REPLACE(REPLACE(REPLACE(player_name, '.', ''), ' Jr', ''), ' Sr', ''), ' III', '') LIKE ? "
                + "LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sqlFuzzy)) {
            ps.setString(1, cleanedName + "%");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return new PlayerInfo(rs.getString("espn_id"), rs.getString("team_abbr"));
        } catch (Exception e) {
            System.out.println("⚠️ DB Fuzzy Match Fehler: " + e.getMessage());
        }

        System.out.println("⚠️ Spieler nicht in players-Tabelle gefunden: " + playerName + " — run ESPNRosterSync zuerst.");
        return new PlayerInfo("0", defaultTeam);
    }

    private static String cleanPlayerName(String playerName) {
        return playerName
                .replace(" Jr.", "").replace(" Jr", "")
                .replace(" Sr.", "").replace(" Sr", "")
                .replace(" III", "").replace(" II", "")
                .replace(".", "")
                .trim();
    }

    // 🔥 HIER IST DER FIX FÜR DAS FRONTEND UND DIE SAISON-DATEN 🔥
    private static JSONObject createPropObject(String playerName, PlayerInfo playerInfo, String matchup, Market market,
                                               double line, double odds, double impliedProbability,
                                               double fairBookieProbability, double prophetProbability,
                                               double edge, boolean isValue, StatValue l5,
                                               StatValue season, double projectedStat, String dataQuality) {
        JSONObject prop = new JSONObject();
        prop.put("playerName", playerName);
        prop.put("playerId", playerInfo.espnId == null ? "0" : playerInfo.espnId);
        prop.put("teamAbbr", playerInfo.teamAbbr == null ? "nba" : playerInfo.teamAbbr);
        prop.put("color", getTeamColor(playerInfo.teamAbbr));
        prop.put("matchup", matchup);
        prop.put("market", market.label);
        prop.put("propLine", round(line, 1));
        prop.put("bookieOdds", round(odds, 2));
        prop.put("impliedProbability", round(impliedProbability, 1));
        prop.put("fairBookieProbability", round(fairBookieProbability, 1));
        prop.put("prophetProb", round(prophetProbability, 1));
        prop.put("edge", round(edge, 1));
        prop.put("isValue", isValue);

        // Beide Namensvarianten speichern, damit das Frontend sie zu 100% findet
        putNullableDouble(prop, "l5Average", l5);
        putNullableDouble(prop, "seasonAverage", season);
        putNullableDouble(prop, "l5", l5);
        putNullableDouble(prop, "season", season);

        prop.put("projectedStat", round(projectedStat, 1));
        prop.put("dataQuality", dataQuality);
        return prop;
    }

    private static void savePropPrediction(Connection conn, String eventId, JSONObject prop) {
        String sql = "INSERT INTO prop_predictions "
                + "(event_id, player_name, player_id, team_abbr, matchup, market, prop_line, bookie_odds, "
                + "implied_probability, fair_bookie_probability, projected_stat, prophet_prob, edge, is_value, "
                + "l5_average, season_average, data_quality, result) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventId);
            ps.setString(2, prop.getString("playerName"));
            ps.setString(3, prop.optString("playerId", "0"));
            ps.setString(4, prop.optString("teamAbbr", "nba"));
            ps.setString(5, prop.optString("matchup", ""));
            ps.setString(6, prop.optString("market", ""));
            ps.setDouble(7, prop.getDouble("propLine"));
            ps.setDouble(8, prop.getDouble("bookieOdds"));
            ps.setDouble(9, prop.getDouble("impliedProbability"));
            ps.setDouble(10, prop.getDouble("fairBookieProbability"));
            ps.setDouble(11, prop.getDouble("projectedStat"));
            ps.setDouble(12, prop.getDouble("prophetProb"));
            ps.setDouble(13, prop.getDouble("edge"));
            ps.setInt(14, prop.getBoolean("isValue") ? 1 : 0);
            setNullableDouble(ps, 15, prop, "l5Average");
            setNullableDouble(ps, 16, prop, "seasonAverage");
            ps.setString(17, prop.getString("dataQuality"));
            ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("⚠️ prop_predictions Insert fehlgeschlagen: " + e.getMessage());
        }
    }

    private static void ensurePropPredictionsTable(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
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
        }
    }

    private static JSONArray sortPropsArray(JSONArray unsortedArray) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < unsortedArray.length(); i++) list.add(unsortedArray.getJSONObject(i));
        Collections.sort(list, Comparator
                .comparing((JSONObject o) -> o.optBoolean("isValue", false) ? 0 : 1)
                .thenComparing((JSONObject o) -> -o.optDouble("edge", -999.0))
                .thenComparing((JSONObject o) -> -o.optDouble("prophetProb", 0.0))
                .thenComparing(o -> o.optString("matchup", ""))
                .thenComparing(o -> o.optString("playerName", "")));
        JSONArray sorted = new JSONArray();
        for (JSONObject obj : list) sorted.put(obj);
        return sorted;
    }

    private static JSONObject selectBookmaker(JSONArray bookmakers, String preferredKey) {
        if (bookmakers == null || bookmakers.length() == 0) return null;
        for (int i = 0; i < bookmakers.length(); i++) {
            JSONObject bookie = bookmakers.getJSONObject(i);
            if (preferredKey.equalsIgnoreCase(bookie.optString("key", ""))) return bookie;
        }
        return bookmakers.getJSONObject(0);
    }

    private static void sendDiscordAlert(HttpClient client, JSONObject prop) {
        if (discordWebhookUrl == null || discordWebhookUrl.trim().isEmpty()) return;
        try {
            String message = String.format(Locale.US,
                    "🚨 **PROPHET EDGE FOUND** 🚨\n**%s** | OVER %.1f %s\n🏀 %s\n📈 Prophet: **%.1f%%** | Fair Bookie: **%.1f%%** | Edge: **%+.1f%%**\n🔥 L5: **%s** | Season: **%s** | Quality: **%s**",
                    prop.getString("playerName"),
                    prop.getDouble("propLine"),
                    prop.getString("market"),
                    prop.getString("matchup"),
                    prop.getDouble("prophetProb"),
                    prop.getDouble("fairBookieProbability"),
                    prop.getDouble("edge"),
                    nullableDisplay(prop, "l5Average"),
                    nullableDisplay(prop, "seasonAverage"),
                    prop.getString("dataQuality"));

            JSONObject payload = new JSONObject();
            payload.put("content", message);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(discordWebhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.out.println("⚠️ Discord Alert fehlgeschlagen: " + e.getMessage());
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Double parseStatCell(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("DNP")) return null;
        s = s.replace(",", ".").replaceAll("[^0-9.\\-]", "");
        if (s.isEmpty() || s.equals("-")) return null;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static void putNullableDouble(JSONObject obj, String key, StatValue stat) {
        if (stat.available) obj.put(key, round(stat.value, 1));
        else obj.put(key, JSONObject.NULL);
    }

    private static void setNullableDouble(PreparedStatement ps, int index, JSONObject obj, String key) throws Exception {
        if (obj.isNull(key)) ps.setNull(index, java.sql.Types.REAL);
        else ps.setDouble(index, obj.getDouble(key));
    }

    private static String nullableDisplay(JSONObject obj, String key) {
        return obj.isNull(key) ? "N/A" : String.format(Locale.US, "%.1f", obj.getDouble(key));
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

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try { return Double.parseDouble(raw.trim()); }
        catch (Exception e) { return fallback; }
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
        if (value == null || value.trim().isEmpty()) value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException("Fehlender Config-Wert: " + key);
        return value.trim();
    }

    private static String optionalConfig(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String getTeamColor(String abbr) {
        if (abbr == null) return "#3b82f6";
        switch (abbr.toLowerCase(Locale.ROOT)) {
            case "atl": return "#e03a3e";
            case "bos": return "#007A33";
            case "bkn": return "#000000";
            case "cha": return "#1D1160";
            case "chi": return "#CE1141";
            case "cle": return "#bc034d";
            case "dal": return "#00538C";
            case "den": return "#0E2240";
            case "det": return "#1D42BA";
            case "gsw": return "#1D428A";
            case "hou": return "#CE1141";
            case "ind": return "#002D62";
            case "lac": return "#C8102E";
            case "lal": return "#552583";
            case "mem": return "#5D76A9";
            case "mia": return "#98002E";
            case "mil": return "#00471B";
            case "min": return "#0C2340";
            case "nop": return "#0C2340";
            case "nyk": return "#F58426";
            case "okc": return "#007AC1";
            case "orl": return "#0077C0";
            case "phi": return "#006BB6";
            case "phx": return "#1D1160";
            case "por": return "#E03A3E";
            case "sac": return "#5A2D81";
            case "sas": return "#C4CED4";
            case "tor": return "#CE1141";
            case "uta": return "#002B5C";
            case "was": return "#002B5C";
            default: return "#3b82f6";
        }
    }
}