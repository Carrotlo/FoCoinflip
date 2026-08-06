package me.foesio.foCoinflip.stats;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.foesio.foCoinflip.config.PluginConfig;
import me.foesio.foCoinflip.FoCoinflip;

public class StatsRepository {

    private final FoCoinflip plugin;
    private final PluginConfig config;
    private final ConcurrentMap<UUID, CachedStats> statsCache = new ConcurrentHashMap<>();
    private final ExecutorService writeExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private String jdbcUrl;

    public StatsRepository(FoCoinflip plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.writeExecutor = Executors.newSingleThreadExecutor(statsThreadFactory(plugin.getName()));
    }

    public void initialize() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder");
        }

        File dbFile = new File(plugin.getDataFolder(), "data.db");
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        plugin.fileLogger().info("Initializing SQLite stats storage.");

        try (Connection connection = openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                        "CREATE TABLE IF NOT EXISTS player_stats ("
                                + "uuid TEXT PRIMARY KEY,"
                                + "total_won REAL NOT NULL DEFAULT 0,"
                                + "total_lost REAL NOT NULL DEFAULT 0,"
                                + "total_sum REAL NOT NULL DEFAULT 0"
                                + ")")) {
                statement.executeUpdate();
            }

            ensureColumn(connection, "net_won");
            ensureColumn(connection, "net_lost");
            loadStatsCache(connection);
            plugin.fileLogger().info("SQLite stats storage initialized.");
        } catch (SQLException exception) {
            plugin.fileLogger().error("Failed to initialize SQLite stats storage", exception);
            throw new IllegalStateException("Failed to initialize SQLite table", exception);
        }
    }

    public PlayerStats getStats(UUID uuid) {
        return toPlayerStats(statsCache.getOrDefault(uuid, CachedStats.ZERO));
    }

    public void recordResolution(UUID winnerId, UUID loserId, double payout, double stake) {
        double winnerProfit = payout - stake;
        statsCache.merge(winnerId, CachedStats.netWon(winnerProfit), CachedStats::plus);
        statsCache.merge(loserId, CachedStats.netLost(stake), CachedStats::plus);

        queuePersistResolution(winnerId, loserId, winnerProfit, stake);
    }

    private void queuePersistResolution(UUID winnerId, UUID loserId, double winnerProfit, double stake) {
        if (closed.get()) {
            plugin.fileLogger().warn("Stats write skipped because repository is closed. winnerUuid=" + winnerId
                    + ", loserUuid=" + loserId);
            return;
        }

        try {
            writeExecutor.execute(() -> persistResolution(winnerId, loserId, winnerProfit, stake));
        } catch (RejectedExecutionException exception) {
            plugin.fileLogger().warn("Stats write skipped because writer is shutting down. winnerUuid=" + winnerId
                    + ", loserUuid=" + loserId);
        }
    }

    private void persistResolution(UUID winnerId, UUID loserId, double winnerProfit, double stake) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO player_stats(uuid, total_won, total_lost, total_sum) VALUES(?, 0, 0, 0)");
                    PreparedStatement winnerUpdate = connection.prepareStatement(
                            "UPDATE player_stats SET net_won = net_won + ? WHERE uuid = ?");
                    PreparedStatement loserUpdate = connection.prepareStatement(
                            "UPDATE player_stats SET net_lost = net_lost + ? WHERE uuid = ?")) {

                insert.setString(1, winnerId.toString());
                insert.executeUpdate();

                insert.setString(1, loserId.toString());
                insert.executeUpdate();

                winnerUpdate.setDouble(1, winnerProfit);
                winnerUpdate.setString(2, winnerId.toString());
                winnerUpdate.executeUpdate();

                loserUpdate.setDouble(1, stake);
                loserUpdate.setString(2, loserId.toString());
                loserUpdate.executeUpdate();

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to update player stats: " + exception.getMessage());
            plugin.fileLogger().error("Failed to update player stats. winnerUuid=" + winnerId + ", loserUuid=" + loserId,
                    exception);
        }
    }

    private void loadStatsCache(Connection connection) throws SQLException {
        statsCache.clear();

        String sql = "SELECT uuid, total_won, total_lost, net_won, net_lost FROM player_stats";
        int loaded = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                try {
                    UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                    statsCache.put(uuid, new CachedStats(
                            resultSet.getDouble("total_won"),
                            resultSet.getDouble("total_lost"),
                            resultSet.getDouble("net_won"),
                            resultSet.getDouble("net_lost")));
                    loaded++;
                } catch (IllegalArgumentException exception) {
                    plugin.fileLogger().warn("Skipping invalid stats UUID in SQLite cache load: "
                            + resultSet.getString("uuid"));
                }
            }
        }

        plugin.fileLogger().info("Loaded " + loaded + " cached player stat rows.");
    }

    private void ensureColumn(Connection connection, String columnName) throws SQLException {
        if (readColumns(connection).contains(columnName)) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE player_stats ADD COLUMN " + columnName + " REAL NOT NULL DEFAULT 0")) {
            statement.executeUpdate();
            plugin.fileLogger().info("Added missing stats column: " + columnName);
        }
    }

    private Set<String> readColumns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(player_stats)");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    private double legacyNetProfit(double legacyPayout) {
        if (legacyPayout <= 0.0D) {
            return 0.0D;
        }

        double taxRate = config.getTaxPercent() / 100.0D;
        double payoutMultiplier = 2.0D * (1.0D - taxRate);
        if (payoutMultiplier <= 0.0D) {
            return 0.0D;
        }

        double estimatedStake = legacyPayout / payoutMultiplier;
        return legacyPayout - estimatedStake;
    }

    private PlayerStats toPlayerStats(CachedStats cachedStats) {
        double totalWon = legacyNetProfit(cachedStats.legacyWon()) + cachedStats.netWon();
        double totalLost = cachedStats.legacyLost() + cachedStats.netLost();
        return new PlayerStats(totalWon, totalLost, totalWon - totalLost);
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        writeExecutor.shutdown();
        try {
            if (!writeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                writeExecutor.shutdownNow();
                plugin.fileLogger().warn("Timed out waiting for async stats writes to finish.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writeExecutor.shutdownNow();
            plugin.fileLogger().warn("Interrupted while waiting for async stats writes to finish.");
        }
    }

    private ThreadFactory statsThreadFactory(String pluginName) {
        return task -> {
            Thread thread = new Thread(task, pluginName + "-StatsWriter");
            thread.setDaemon(true);
            return thread;
        };
    }

    private record CachedStats(double legacyWon, double legacyLost, double netWon, double netLost) {
        private static final CachedStats ZERO = new CachedStats(0, 0, 0, 0);

        private static CachedStats netWon(double amount) {
            return new CachedStats(0, 0, amount, 0);
        }

        private static CachedStats netLost(double amount) {
            return new CachedStats(0, 0, 0, amount);
        }

        private CachedStats plus(CachedStats other) {
            return new CachedStats(
                    legacyWon + other.legacyWon,
                    legacyLost + other.legacyLost,
                    netWon + other.netWon,
                    netLost + other.netLost);
        }
    }
}
