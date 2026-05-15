import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class PropResultPuller {
    private static final String DB_URL = "jdbc:sqlite:nba_secret_weapon.db";
    private static final String SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=";
    private static final String SUMMARY_URL = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/summary?event=";

    private static final DateTimeFormatter ESPN_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId NBA_ZONE = ZoneId.of("America/New_York");

    public static void main(String[] args) {
        System.out.println("🔍 Prophet Backtest: Suche fertige NBA-Spiele und werte Player Props aus...");

        try {
            HttpClient client = HttpClient.newHttpClient();
            List<CompletedGame> completedGames = fetchCompletedGames(client, 4);
            if (completedGames.isEmpty()) {
                System.out.println("ℹ️ Keine abgeschlossenen Spiele in den letzten Tagen gefunden.");
                return;
            }

            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                Set<String> cols = getColumns(conn, "prop_predictions");
                if (cols.isEmpty()) {
                    System.out.println("❌ Tabelle prop_predictions fehlt. Bitte zuerst PropBacktestSetup ausführen.");
                    return;
                }

                ColumnMap c = new ColumnMap(cols);
                ensureRequiredResultColumns(cols);

                List<PredictionRow> pending = readPendingPredictions(conn, c);
                if (pending.isEmpty()) {
                    System.out.println("ℹ️ Keine offenen Prop Predictions gefunden.");
                    return;
                }

                Map<String, Map<String, PlayerLine>> boxscoreCache = new HashMap<>();
                int evaluated = 0;
                int waiting = 0;
                int missingStats = 0;

                for (PredictionRow row : pending) {
                    CompletedGame game = findMatchingGame(row.matchup, completedGames);
                    if (game == null) {
                        waiting++;
                        continue;
                    }

                    Map<String, PlayerLine> boxscore = boxscoreCache.get(game.eventId);
                    if (boxscore == null) {
                        boxscore = fetchBoxscore(client, game.eventId);
                        boxscoreCache.put(game.eventId, boxscore);
                    }

                    PlayerLine playerLine = findPlayerLine(boxscore, row.playerId, row.playerName);
                    if (playerLine == null) {
                        missingStats++;
                        updateStatus(conn, c, row.rowId, "WAITING_STATS");
                        System.out.println("⚠️ Keine Boxscore-Stats gefunden für: " + row.playerName + " | " + row.matchup);
                        continue;
                    }

                    Double actualStat = getActualStat(playerLine, row.market);
                    if (actualStat == null) {
                        missingStats++;
                        updateStatus(conn, c, row.rowId, "WAITING_STATS");
                        System.out.println("⚠️ Stat nicht gefunden: " + row.playerName + " | " + row.market);
                        continue;
                    }

                    Result result = gradeOver(row.propLine, row.bookieOdds, actualStat);
                    updateResult(conn, c, row.rowId, actualStat, result.result, result.profit);
                    evaluated++;

                    System.out.printf(Locale.US,
                            "✅ %s | OVER %.1f %s | Actual %.1f | %s | Profit %.2f%n",
                            row.playerName, row.propLine, row.market, actualStat, result.result, result.profit);
                }

                System.out.println("\n🎯 Backtest fertig.");
                System.out.println("✅ Ausgewertet: " + evaluated);
                System.out.println("⏳ Noch nicht spielbereit: " + waiting);
                System.out.println("⚠️ Stats fehlen noch: " + missingStats);
            }
        } catch (Exception e) {
            System.err.println("❌ Fehler im PropResultPuller:");
            e.printStackTrace();
        }
    }

    private static List<CompletedGame> fetchCompletedGames(HttpClient client, int daysBack) throws Exception {
        List<CompletedGame> games = new ArrayList<>();
        LocalDate today = LocalDate.now(NBA_ZONE);

        for (int d = 0; d < daysBack; d++) {
            LocalDate date = today.minusDays(d);
            String url = SCOREBOARD_URL + ESPN_DATE.format(date);
            JSONObject root = new JSONObject(makeRequest(client, url));
            JSONArray events = root.optJSONArray("events");
            if (events == null) continue;

            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                JSONObject statusType = event.optJSONObject("status") == null ? null : event.getJSONObject("status").optJSONObject("type");
                boolean completed = statusType != null && (statusType.optBoolean("completed", false) || "STATUS_FULL".equalsIgnoreCase(statusType.optString("name")));
                if (!completed) continue;

                JSONObject competition = event.getJSONArray("competitions").getJSONObject(0);
                JSONArray competitors = competition.getJSONArray("competitors");
                String home = null;
                String away = null;

                for (int c = 0; c < competitors.length(); c++) {
                    JSONObject comp = competitors.getJSONObject(c);
                    String displayName = comp.getJSONObject("team").optString("displayName");
                    String homeAway = comp.optString("homeAway");
                    if ("home".equalsIgnoreCase(homeAway)) home = displayName;
                    if ("away".equalsIgnoreCase(homeAway)) away = displayName;
                }

                if (home != null && away != null) {
                    games.add(new CompletedGame(event.getString("id"), home, away));
                    System.out.println("🏁 Fertig: " + home + " vs " + away + " | ESPN Event " + event.getString("id"));
                }
            }
        }
        return games;
    }

    private static Map<String, PlayerLine> fetchBoxscore(HttpClient client, String eventId) throws Exception {
        Map<String, PlayerLine> map = new HashMap<>();
        JSONObject root = new JSONObject(makeRequest(client, SUMMARY_URL + eventId));
        JSONObject boxscore = root.optJSONObject("boxscore");
        if (boxscore == null) return map;

        JSONArray teams = boxscore.optJSONArray("players");
        if (teams == null) return map;

        for (int t = 0; t < teams.length(); t++) {
            JSONObject teamBlock = teams.getJSONObject(t);
            JSONArray categories = teamBlock.optJSONArray("statistics");
            if (categories == null) continue;

            for (int c = 0; c < categories.length(); c++) {
                JSONObject category = categories.getJSONObject(c);
                JSONArray labels = category.optJSONArray("labels");
                JSONArray athletes = category.optJSONArray("athletes");
                if (labels == null || athletes == null) continue;

                int ptsIdx = findIndex(labels, "PTS");
                int rebIdx = findIndex(labels, "REB");
                int astIdx = findIndex(labels, "AST");
                if (ptsIdx < 0 && rebIdx < 0 && astIdx < 0) continue;

                for (int a = 0; a < athletes.length(); a++) {
                    JSONObject athleteLine = athletes.getJSONObject(a);
                    JSONObject athlete = athleteLine.optJSONObject("athlete");
                    JSONArray stats = athleteLine.optJSONArray("stats");
                    if (athlete == null || stats == null) continue;

                    PlayerLine line = new PlayerLine();
                    line.id = athlete.optString("id", "");
                    line.name = athlete.optString("displayName", athlete.optString("fullName", ""));
                    line.points = parseStat(stats, ptsIdx);
                    line.rebounds = parseStat(stats, rebIdx);
                    line.assists = parseStat(stats, astIdx);

                    if (!line.id.isEmpty()) map.put("id:" + line.id, line);
                    if (!line.name.isEmpty()) map.put("name:" + normalizeName(line.name), line);
                }
            }
        }
        return map;
    }

    private static List<PredictionRow> readPendingPredictions(Connection conn, ColumnMap c) throws Exception {
        List<PredictionRow> rows = new ArrayList<>();
        String sql = "SELECT rowid, * FROM prop_predictions WHERE " + q(c.result) + " IS NULL OR " + q(c.result) + " = '' OR " + q(c.status) + " IN ('PENDING','WAITING_STATS')";

        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                PredictionRow row = new PredictionRow();
                row.rowId = rs.getLong("rowid");
                row.playerName = getString(rs, c.playerName, "");
                row.playerId = getString(rs, c.playerId, "");
                row.matchup = getString(rs, c.matchup, "");
                row.market = normalizeMarket(getString(rs, c.market, ""));
                row.propLine = getDouble(rs, c.propLine, Double.NaN);
                row.bookieOdds = getDouble(rs, c.bookieOdds, 0.0);

                if (row.playerName.isEmpty() || row.matchup.isEmpty() || row.market.isEmpty() || Double.isNaN(row.propLine)) {
                    continue;
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static CompletedGame findMatchingGame(String matchup, List<CompletedGame> completedGames) {
        String normalized = normalizeMatchup(matchup);
        for (CompletedGame game : completedGames) {
            if (normalized.equals(game.normalizedHomeAway) || normalized.equals(game.normalizedAwayHome)) {
                return game;
            }
        }
        return null;
    }

    private static PlayerLine findPlayerLine(Map<String, PlayerLine> boxscore, String playerId, String playerName) {
        if (playerId != null && !playerId.trim().isEmpty()) {
            PlayerLine byId = boxscore.get("id:" + playerId.trim());
            if (byId != null) return byId;
        }
        if (playerName != null && !playerName.trim().isEmpty()) {
            PlayerLine byName = boxscore.get("name:" + normalizeName(playerName));
            if (byName != null) return byName;
        }
        return null;
    }

    private static Double getActualStat(PlayerLine line, String market) {
        String m = normalizeMarket(market);
        if (m.contains("POINT")) return line.points;
        if (m.contains("REBOUND")) return line.rebounds;
        if (m.contains("ASSIST")) return line.assists;
        return null;
    }

    private static Result gradeOver(double line, double odds, double actualStat) {
        Result r = new Result();
        if (actualStat > line) {
            r.result = "WIN";
            r.profit = odds > 0 ? odds - 1.0 : 0.0;
        } else if (actualStat == line) {
            r.result = "PUSH";
            r.profit = 0.0;
        } else {
            r.result = "LOSS";
            r.profit = -1.0;
        }
        return r;
    }

    private static void updateResult(Connection conn, ColumnMap c, long rowId, double actualStat, String result, double profit) throws Exception {
        String sql = "UPDATE prop_predictions SET " +
                q(c.actualStat) + " = ?, " +
                q(c.result) + " = ?, " +
                q(c.profit) + " = ?, " +
                q(c.status) + " = 'EVALUATED', " +
                q(c.evaluatedAt) + " = CURRENT_TIMESTAMP " +
                "WHERE rowid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, actualStat);
            ps.setString(2, result);
            ps.setDouble(3, profit);
            ps.setLong(4, rowId);
            ps.executeUpdate();
        }
    }

    private static void updateStatus(Connection conn, ColumnMap c, long rowId, String status) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE prop_predictions SET " + q(c.status) + " = ? WHERE rowid = ?")) {
            ps.setString(1, status);
            ps.setLong(2, rowId);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    private static void ensureRequiredResultColumns(Set<String> cols) {
        String[] required = {"result", "status", "actual_stat", "profit", "evaluated_at"};
        for (String col : required) {
            if (!cols.contains(col.toLowerCase())) {
                System.out.println("⚠️ Spalte fehlt: " + col + " — bitte PropBacktestSetup ausführen.");
            }
        }
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

    private static String getString(ResultSet rs, String col, String def) {
        try {
            String v = rs.getString(col);
            return v == null ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private static double getDouble(ResultSet rs, String col, double def) {
        try {
            double v = rs.getDouble(col);
            return rs.wasNull() ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private static String makeRequest(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + " for " + url + " | Body: " + response.body());
        }
        return response.body();
    }

    private static int findIndex(JSONArray arr, String value) {
        for (int i = 0; i < arr.length(); i++) {
            if (value.equalsIgnoreCase(arr.optString(i))) return i;
        }
        return -1;
    }

    private static Double parseStat(JSONArray stats, int idx) {
        if (idx < 0 || idx >= stats.length()) return null;
        try {
            String raw = stats.getString(idx).replace("+", "").trim();
            if (raw.isEmpty() || raw.equals("-")) return null;
            return Double.parseDouble(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeMarket(String market) {
        if (market == null) return "";
        String m = market.toUpperCase(Locale.ROOT).replace("PLAYER_", "").trim();
        if (m.equals("PTS")) return "POINTS";
        if (m.equals("REB")) return "REBOUNDS";
        if (m.equals("AST")) return "ASSISTS";
        return m;
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT)
                .replace("jr.", "").replace("jr", "")
                .replace("sr.", "").replace("sr", "")
                .replace("iii", "").replace("ii", "")
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }

    private static String normalizeTeam(String team) {
        if (team == null) return "";
        return team.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String normalizeMatchup(String matchup) {
        if (matchup == null) return "";
        String[] parts = matchup.split("(?i)\\s+vs\\s+");
        if (parts.length == 2) return normalizeTeam(parts[0]) + "vs" + normalizeTeam(parts[1]);
        return normalizeTeam(matchup);
    }

    private static class ColumnMap {
        String playerName;
        String playerId;
        String matchup;
        String market;
        String propLine;
        String bookieOdds;
        String result;
        String status;
        String actualStat;
        String profit;
        String evaluatedAt;

        ColumnMap(Set<String> cols) {
            playerName = choose(cols, "player_name", "player_name", "playerName");
            playerId = choose(cols, "player_id", "player_id", "playerId");
            matchup = choose(cols, "matchup", "matchup");
            market = choose(cols, "market", "market", "prop_type", "propType");
            propLine = choose(cols, "prop_line", "prop_line", "propLine", "line");
            bookieOdds = choose(cols, "bookie_odds", "bookie_odds", "bookieOdds", "over_odds", "overOdds");
            result = choose(cols, "result", "result");
            status = choose(cols, "status", "status");
            actualStat = choose(cols, "actual_stat", "actual_stat", "actualStat");
            profit = choose(cols, "profit", "profit");
            evaluatedAt = choose(cols, "evaluated_at", "evaluated_at", "evaluatedAt");
        }
    }

    private static class PredictionRow {
        long rowId;
        String playerName;
        String playerId;
        String matchup;
        String market;
        double propLine;
        double bookieOdds;
    }

    private static class CompletedGame {
        String eventId;
        String home;
        String away;
        String normalizedHomeAway;
        String normalizedAwayHome;

        CompletedGame(String eventId, String home, String away) {
            this.eventId = eventId;
            this.home = home;
            this.away = away;
            this.normalizedHomeAway = normalizeTeam(home) + "vs" + normalizeTeam(away);
            this.normalizedAwayHome = normalizeTeam(away) + "vs" + normalizeTeam(home);
        }
    }

    private static class PlayerLine {
        String id;
        String name;
        Double points;
        Double rebounds;
        Double assists;
    }

    private static class Result {
        String result;
        double profit;
    }
}
