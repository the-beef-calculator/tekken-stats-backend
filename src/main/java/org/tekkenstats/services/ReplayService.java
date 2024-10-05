package org.tekkenstats.services;

import com.google.common.hash.BloomFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;
import org.tekkenstats.Battle;
import org.tekkenstats.CharacterStats;
import org.tekkenstats.PastPlayerNames;
import org.tekkenstats.Player;
import org.tekkenstats.configuration.RabbitMQConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.tekkenstats.repositories.BattleRepository;
import org.tekkenstats.repositories.PastPlayerNamesRepository;
import org.tekkenstats.repositories.PlayerRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReplayService {

    private static final Logger logger = LogManager.getLogger(ReplayService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();


    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BattleService battleService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME, concurrency = "4")
    public void receiveMessage(String message, @Header("unixTimestamp") String dateAndTime) throws Exception
    {
        String threadName = Thread.currentThread().getName();
        logger.info("Thread: {}, Received Battle Data from RabbitMQ, Timestamped: {}", threadName, dateAndTime);

        long startTime = System.currentTimeMillis();

        List<Battle> battles = objectMapper.readValue(message, new TypeReference<List<Battle>>() {
        });
        processBattlesAsync(battles);

        long endTime = System.currentTimeMillis();

        logger.info("Thread: {}, Total Operation Time: {} ms", threadName, endTime - startTime);
    }

    public void processBattlesAsync(List<Battle> battles)
    {
        // Extract battle IDs and player IDs
        Set<String> battleIDs = new HashSet<>();
        Set<String> playerIDs = new HashSet<>();
        extractBattleAndPlayerIDs(battles, battleIDs, playerIDs);

        // Fetch existing battles and players
        Map<String, Battle> mapOfExistingBattles = fetchExistingBattles(battleIDs);
        //Map<String, Player> mapOfExistingPlayers = fetchExistingPlayers(playerIDs);

        Set<Player> updatedPlayers = new HashSet<>();
        Set<Battle> battleSet = new HashSet<>();

        // Instantiate objects and update relevant information
        processBattlesAndPlayers(battles, mapOfExistingBattles, updatedPlayers,battleSet);

        // Execute player batched writes
        executePlayerBulkOperations(updatedPlayers);
        executeCharacterStatsBulkOperations(updatedPlayers);

        // Execute battle batched writes
        executeBattleBatchWrite(battleSet);
    }

    private void extractBattleAndPlayerIDs(List<Battle> battles, Set<String> battleIDs, Set<String> playerIDs)
    {
        for (Battle battle : battles) {
            battleIDs.add(battle.getBattleId());
            playerIDs.add(battle.getPlayer1UserID());
            playerIDs.add(battle.getPlayer2UserID());
        }
    }


    private Map<String, Battle> fetchExistingBattles(Set<String> battleIDs)
    {
        long startTime = System.currentTimeMillis();
        int battleIdSize = battleIDs.size();
        final double THRESHOLD = 0.5; // 50%

        if (battleIDs.isEmpty()) {
            logger.warn("No battle IDs provided. Skipping database fetch.");
            return Collections.emptyMap();
        }

        // Access the Bloom Filter from BattleService
        BloomFilter<String> bloomFilter = battleService.getBattleIdBloomFilter();


        // Count how many battle IDs are positive matches in the Bloom Filter
        int positiveCount = 0;

        for (String battleId : battleIDs)
        {
            if (bloomFilter.mightContain(battleId))
            {
                positiveCount++;

                if (((double) positiveCount /battleIdSize) >= THRESHOLD)
                {
                    break;
                }
            }
        }

        double positivePercentage = (double) positiveCount / battleIdSize;
        logger.info("Positive matches in Bloom Filter: {}%", positivePercentage * 100);

        List<Battle> existingBattles;

        if (positivePercentage >= THRESHOLD)
        {
            // Fetch recent battles from the database
            String sql = "SELECT * FROM battles ORDER BY battle_at DESC LIMIT 250000";
            existingBattles = jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(Battle.class));
            logger.info("Fetched {} recent battles from database.", existingBattles.size());

            // Filter the battles to include only those with IDs in battleIDs
            Set<String> battleIdSet = new HashSet<>(battleIDs);
            existingBattles = existingBattles.stream()
                    .filter(battle -> battleIdSet.contains(battle.getBattleId()))
                    .collect(Collectors.toList());
        }
        else
        {
            logger.info("Battles fell below bloom filter threshold. Skipping database read.");
            long endTime = System.currentTimeMillis();
            logger.info("Bloom filter check and skip took {} ms", (endTime - startTime));

            return Collections.emptyMap();
        }
        long endTime = System.currentTimeMillis();
        logger.info("Retrieved existing battles from database in {} ms", (endTime - startTime));


        return existingBattles.stream()
                .collect(Collectors.toMap(Battle::getBattleId, battle -> battle));
    }


    private Map<String, Player> fetchExistingPlayers(Set<String> playerIDs) {
        if (playerIDs.isEmpty()) {
            logger.warn("No player IDs provided. Skipping database fetch.");
            return Collections.emptyMap();
        }

        long startTime = System.currentTimeMillis();

        String sql = "SELECT * FROM public.players WHERE user_id IN (:playerIDs)";

        MapSqlParameterSource parameters = new MapSqlParameterSource();
        parameters.addValue("playerIDs", playerIDs);

        List<Player> existingPlayers = namedParameterJdbcTemplate.query(
                sql,
                parameters,
                (rs, rowNum) -> {
                    Player player = new Player();
                    player.setPlayerId(rs.getString("user_id"));
                    player.setName(rs.getString("name"));
                    player.setPolarisId(rs.getString("polaris_id"));
                    player.setTekkenPower(rs.getLong("tekken_power"));
                    player.setLatestBattle(rs.getLong("latest_battle"));
                    return player;
                }
        );

        long endTime = System.currentTimeMillis();
        logger.info("Retrieved existing players from database: {} ms", (endTime - startTime));

        return existingPlayers.stream()
                .collect(Collectors.toMap(Player::getPlayerId, player -> player));
    }

    private void processBattlesAndPlayers(
            List<Battle> battles,
            Map<String, Battle> existingBattlesMap,
            Set<Player> updatedPlayers,
            Set<Battle> battleSet)
    {

        long startTime = System.currentTimeMillis();
        int duplicateBattles = 0;

        for (Battle battle : battles)
        {
            if (!existingBattlesMap.containsKey(battle.getBattleId()))
            {
                battle.setDate(getReadableDateInUTC(battle));

                Player player1 = new Player();
                updateNewPlayerDetails(player1, battle, 1);
                Player player2 = new Player();
                updateNewPlayerDetails(player2, battle, 2);

                updatePlayerWithBattle(player1, battle, 1);
                updatePlayerWithBattle(player2, battle, 2);

                updatedPlayers.add(player1);
                updatedPlayers.add(player2);

                // Queue insert operation for battle
                battleSet.add(battle);
            } else
            {
                duplicateBattles++;
            }
        }
        long endTime = System.currentTimeMillis();
        if (duplicateBattles == battles.size()) {
            logger.warn("Entire batch already exists in database!");
            return;
        }
        logger.info("Updated player and battle information: {} ms", (endTime - startTime));
    }

    @Transactional
    public void executeBattleBatchWrite(Set<Battle> battleSet)
    {
        if (battleSet == null || battleSet.isEmpty()) {
            logger.warn("No battles to insert or update.");
            return;
        }

        try {
            long startTime = System.currentTimeMillis();

            String sql = "INSERT INTO battles (" +
                    "battle_id, date, battle_at, battle_type, game_version, " +
                    "player1_character_id, player1_name, player1_polaris_id, player1_tekken_power, player1_dan_rank, " +
                    "player1_rating_before, player1_rating_change, player1_rounds_won, player1_userid, " +
                    "player2_character_id, player2_name, player2_polaris_id, player2_tekken_power, player2_dan_rank, " +
                    "player2_rating_before, player2_rating_change, player2_rounds_won, player2_userid, " +
                    "stageid, winner" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (battle_id) DO NOTHING";

            List<Object[]> batchArgs = new ArrayList<>();

            for (Battle battle : battleSet) {
                Object[] args = new Object[] {
                        battle.getBattleId(),
                        battle.getDate(),
                        battle.getBattleAt(),
                        battle.getBattleType(),
                        battle.getGameVersion(),
                        battle.getPlayer1CharacterID(),
                        battle.getPlayer1Name(),
                        battle.getPlayer1PolarisID(),
                        battle.getPlayer1TekkenPower(),
                        battle.getPlayer1DanRank(),
                        battle.getPlayer1RatingBefore(),
                        battle.getPlayer1RatingChange(),
                        battle.getPlayer1RoundsWon(),
                        battle.getPlayer1UserID(),
                        battle.getPlayer2CharacterID(),
                        battle.getPlayer1Name(),
                        battle.getPlayer2PolarisID(),
                        battle.getPlayer2TekkenPower(),
                        battle.getPlayer2DanRank(),
                        battle.getPlayer2RatingBefore(),
                        battle.getPlayer2RatingChange(),
                        battle.getPlayer2RoundsWon(),
                        battle.getPlayer2UserID(),
                        battle.getStageID(),
                        battle.getWinner()
                };
                batchArgs.add(args);
            }

            jdbcTemplate.batchUpdate(sql, batchArgs);

            long endTime = System.currentTimeMillis();
            logger.info("Battle Insertion: {} ms, Inserted/Updated: {}", (endTime - startTime), battleSet.size());

        } catch (Exception e) {
            logger.error("BATTLE INSERTION FAILED: ", e);
        }
    }

    @Transactional
    public void executePlayerBulkOperations(Set<Player> updatedPlayersSet)
    {

        if (updatedPlayersSet.isEmpty()) {
            logger.warn("Updated Player Set is empty! (Battle batch already existed in database)");
            return;
        }

        long startTime = System.currentTimeMillis();

        String sql = "INSERT INTO players (user_id, name, polaris_id, tekken_power,latest_battle) " +
                "VALUES (?, ?, ?, ? ,?) " +
                "ON CONFLICT (user_id) DO UPDATE SET " +
                "tekken_power = EXCLUDED.tekken_power, " +
                "latest_battle = EXCLUDED.latest_battle";

        List<Object[]> batchArgs = new ArrayList<>();

        for (Player updatedPlayer : updatedPlayersSet) {

            Object[] args = new Object[]{
                    updatedPlayer.getPlayerId(),
                    updatedPlayer.getName(),
                    updatedPlayer.getPolarisId(),
                    updatedPlayer.getTekkenPower(),
                    updatedPlayer.getLatestBattle()
            };

            batchArgs.add(args);

        }
        // Execute batch update
        jdbcTemplate.batchUpdate(sql, batchArgs);

        long endTime = System.currentTimeMillis();
        logger.info("Player Bulk Upsert: {} ms, Processed Players: {}",
                (endTime - startTime), updatedPlayersSet.size());
    }

    @Transactional
    public void executeCharacterStatsBulkOperations(
            Set<Player> updatedPlayersSet) {

        if (updatedPlayersSet.isEmpty()) {
            logger.warn("Updated Player Set is empty! (Battle batch already existed in database)");
            return;
        }

        long startTime = System.currentTimeMillis();

        String sql = "INSERT INTO character_stats (player_id, character_id, dan_rank, latest_battle, wins, losses) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (player_id, character_id) DO UPDATE SET " +
                "dan_rank = EXCLUDED.dan_rank, " +
                "latest_battle = EXCLUDED.latest_battle, " +
                "wins = character_stats.wins + EXCLUDED.wins, " +
                "losses = character_stats.losses + EXCLUDED.losses";

        List<Object[]> batchArgs = new ArrayList<>();

        for (Player updatedPlayer : updatedPlayersSet) {
            String userId = updatedPlayer.getPlayerId();

            Map<String, CharacterStats> updatedCharacterStats = updatedPlayer.getCharacterStats();
            if (updatedCharacterStats != null) {
                for (Map.Entry<String, CharacterStats> entry : updatedCharacterStats.entrySet()) {
                    String characterName = entry.getKey();
                    CharacterStats updatedStats = entry.getValue();

                    int winsIncrement = updatedStats.getWinsIncrement();
                    int lossesIncrement = updatedStats.getLossIncrement();

                    Object[] args = new Object[]{
                            userId,
                            characterName,
                            updatedStats.getDanRank(),
                            updatedStats.getLatestBattle(),
                            winsIncrement,
                            lossesIncrement
                    };

                    batchArgs.add(args);

                    // Reset increments after processing
                    updatedStats.setWinsIncrement(0);
                    updatedStats.setLossIncrement(0);
                }
            }
        }
        // Sorting to reduce the rate of deadlocks occurring
        batchArgs.sort(Comparator.comparing((Object[] args) -> (String) args[0]) // player_id
                .thenComparing(args -> (String) args[1]));     // character_id

        int maxRetries = 3;
        int retryCount = 0;
        boolean success = false;

        while (!success && retryCount <= maxRetries) {
            try
            {
                int batchSize = 1000; // Adjust based on your system's capacity
                int totalBatches = (int) Math.ceil((double) batchArgs.size() / batchSize);

                for (int i = 0; i < totalBatches; i++)
                {
                    int start = i * batchSize;
                    int end = Math.min(start + batchSize, batchArgs.size());

                    List<Object[]> batch = batchArgs.subList(start, end);

                    jdbcTemplate.batchUpdate(sql, batch);
                }
                success = true; // If execution reaches here, the operation succeeded
            }
            catch (DataAccessException e)
            {
                Throwable rootCause = e.getRootCause();
                if (rootCause instanceof SQLException)
                {
                    SQLException sqlEx = (SQLException) rootCause;
                    if ("40P01".equals(sqlEx.getSQLState()))
                    {
                        // **Deadlock detected, retrying**
                        retryCount++;
                        logger.warn("Deadlock detected during character_stats batch update. Retrying... attempt {}/{}", retryCount, maxRetries);
                        try
                        {
                            // Exponential backoff
                            Thread.sleep((long) Math.pow(2, retryCount) * 100);
                        } catch (InterruptedException ie)
                        {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Thread interrupted during retry sleep", ie);
                        }
                    } else
                    {
                        // Other SQL exception
                        throw e;
                    }
                } else
                {
                    // Non-SQL exception
                    throw e;
                }
            }
        }

        if (!success)
        {
            throw new RuntimeException("Failed to execute character_stats batch update after " + maxRetries + " attempts due to deadlocks.");
        }

        long endTime = System.currentTimeMillis();
        logger.info("CharacterStats Bulk Upsert: {} ms, Total Processed CharacterStats: {}",
                (endTime - startTime), batchArgs.size());
    }

    private Player getOrCreatePlayer(Map<String, Player> playerMap, Battle battle, int playerNumber) {
        String userId = playerNumber == 1 ? battle.getPlayer1UserID() : battle.getPlayer2UserID();
        Player player = playerMap.get(userId);

        if (player != null)
        {
            return updateExistingPlayer(player, battle, playerNumber);
        }
        else
        {
            Player newPlayer = createNewPlayer(battle, playerNumber);
            playerMap.put(userId, newPlayer);
            return newPlayer;
        }
    }

    private Player updateExistingPlayer(Player player, Battle battle, int playerNumber) {
        addPlayerNameIfNew(player, getPlayerName(battle, playerNumber));
        return player;
    }

    private Player createNewPlayer(Battle battle, int playerNumber)
    {
        Player newPlayer = new Player();
        updateNewPlayerDetails(newPlayer, battle, playerNumber);
        return newPlayer;
    }

    private void updatePlayerWithBattle(Player player, Battle battle, int playerNumber) {
        // Get the character ID associated with the player for this battle
        String characterId = getPlayerCharacter(battle, playerNumber);

        // Fetch or initialize CharacterStats for this character
        CharacterStats stats = player.getCharacterStats().get(characterId);

        if (stats == null)
        {
            // Initialize a new CharacterStats object if none exists for the character
            stats = new CharacterStats();
            stats.setDanRank(getPlayerDanRank(battle, playerNumber));
            player.getCharacterStats().put(characterId, stats);
        }

        // Update wins or losses based on the battle result
        if (battle.getWinner() == playerNumber)
        {
            stats.setWinsIncrement(stats.getWinsIncrement() + 1);
            stats.setWins(stats.getWins() + 1);
        } else
        {
            stats.setLossIncrement(stats.getLossIncrement() + 1);
            stats.setLosses(stats.getLosses() + 1);
        }

        // Only update the danRank and latest battle if this battle is newer than the current latest one
        if (battle.getBattleAt() > stats.getLatestBattle())
        {
            stats.setLatestBattle(battle.getBattleAt());
            player.setLatestBattle();
            player.updateTekkenPower((getPlayerCharacter(battle, playerNumber).equals("1") ? battle.getPlayer1TekkenPower() : battle.getPlayer2TekkenPower()), battle.getBattleAt());
            stats.setDanRank(getPlayerDanRank(battle, playerNumber));  // Update dan rank only if the battle is the latest
        }
    }


    private void updateNewPlayerDetails(Player player, Battle battle, int playerNumber) {
        // Set player-level details
        player.setPlayerId(getPlayerUserId(battle, playerNumber));
        player.setName(getPlayerName(battle, playerNumber));
        player.setPolarisId(getPlayerPolarisId(battle, playerNumber));
        player.setTekkenPower(getPlayerTekkenPower(battle, playerNumber));

        // Create a new CharacterStats object for the player's character
        CharacterStats characterStats = new CharacterStats();
        characterStats.setDanRank(getPlayerDanRank(battle, playerNumber));
        characterStats.setLatestBattle(battle.getBattleAt());

        // Add the new character stats to the player's map, keyed by character ID
        String characterId = getPlayerCharacter(battle, playerNumber);
        player.getCharacterStats().put(characterId, characterStats);
        player.setLatestBattle();

        // Add the player's name to the name history if it's new
        addPlayerNameIfNew(player, player.getName());
    }


    private void addPlayerNameIfNew(Player player, String name)
    {
        if (player.getPlayerNames().stream().noneMatch(pastName -> pastName.getName().equals(name)))
        {
            PastPlayerNames pastName = new PastPlayerNames(name, player);
            player.getPlayerNames().add(pastName);
        }
    }

    private String getReadableDateInUTC(Battle battle) {
        return Instant.ofEpochSecond(battle.getBattleAt())
                .atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm 'UTC'"));
    }

    private String getPlayerName(Battle battle, int playerNumber) {
        return playerNumber == 1 ? battle.getPlayer1Name() : battle.getPlayer2Name();
    }


    private String getPlayerCharacter(Battle battle, int playerNumber) {
        return playerNumber == 1 ? String.valueOf(battle.getPlayer1CharacterID()) : String.valueOf(battle.getPlayer2CharacterID());
    }


    private String getPlayerUserId(Battle battle, int playerNumber) {
        return playerNumber == 1 ? battle.getPlayer1UserID() : battle.getPlayer2UserID();
    }

    private String getPlayerPolarisId(Battle battle, int playerNumber) {
        return playerNumber == 1 ? battle.getPlayer1PolarisID() : battle.getPlayer2PolarisID();
    }

    private long getPlayerTekkenPower(Battle battle, int playerNumber) {
        return playerNumber == 1 ? battle.getPlayer1TekkenPower() : battle.getPlayer2TekkenPower();
    }

    private int getPlayerDanRank(Battle battle, int playerNumber) {
        return playerNumber == 1 ? battle.getPlayer1DanRank() : battle.getPlayer2DanRank();
    }
    private int calculatePlayerRating(Battle battle, int playerNumber) {
        if (playerNumber == 1) {
            return (battle.getPlayer1RatingBefore() != null ? battle.getPlayer1RatingBefore() : 0) +
                    (battle.getPlayer1RatingChange() != null ? battle.getPlayer1RatingChange() : 0);
        } else {
            return (battle.getPlayer2RatingBefore() != null ? battle.getPlayer2RatingBefore() : 0) +
                    (battle.getPlayer2RatingChange() != null ? battle.getPlayer2RatingChange() : 0);
        }
    }
}
