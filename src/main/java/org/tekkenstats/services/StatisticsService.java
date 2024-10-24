package org.tekkenstats.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.tekkenstats.aggregations.AggregatedStatistic;
import org.tekkenstats.aggregations.AggregatedStatisticId;
import org.tekkenstats.aggregations.PlayerCharacterData;
import org.tekkenstats.repositories.AggregatedStatisticsRepository;
import org.tekkenstats.repositories.CharacterStatsRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StatisticsService {

    @Autowired
    private CharacterStatsRepository characterStatsRepository;

    @Autowired
    private AggregatedStatisticsRepository aggregatedStatisticsRepository;

    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);

    @Scheduled(fixedRate = 60000)
    public void computeStatistics() {
        try {
            logger.info("Computing statistics");
            Optional<List<Integer>> gameVersions = fetchGameVersions();
            if (!gameVersions.isEmpty()) {
                for (int gameVersion : gameVersions.get()) {
                    processGameVersionStatistics(gameVersion);
                }
            } else {
                logger.warn("No game versions found.");
            }
        } catch (Exception e) {
            logger.error("Error computing statistics: ", e);
        }
    }

    private Optional<List<Integer>> fetchGameVersions() {
        return characterStatsRepository.findAllGameVersions();
    }

    private void processGameVersionStatistics(int gameVersion) {
        logger.info("Processing statistics for game version: " + gameVersion);
        List<Object[]> allStats = characterStatsRepository.findAllStatsByGameVersion(gameVersion);

        // Load existing statistics for 'standard' category
        List<AggregatedStatistic> existingStatsStandard = aggregatedStatisticsRepository.findByIdGameVersionAndIdCategory(gameVersion, "standard");
        Map<AggregatedStatisticId, AggregatedStatistic> existingStatsMapStandard = existingStatsStandard.stream()
                .collect(Collectors.toMap(AggregatedStatistic::getId, Function.identity()));

        Map<String, PlayerCharacterData> playerMainCharacters = identifyPlayerMainCharacters(allStats);
        Map<AggregatedStatisticId, AggregatedStatistic> mainAggregatedData = aggregateStatistics(playerMainCharacters, "standard", gameVersion, existingStatsMapStandard);
        saveAggregatedStatistics(mainAggregatedData.values());

        // Load existing statistics for 'overall' category
        List<AggregatedStatistic> existingStatsOverall = aggregatedStatisticsRepository.findByIdGameVersionAndIdCategory(gameVersion, "overall");
        Map<AggregatedStatisticId, AggregatedStatistic> existingStatsMapOverall = existingStatsOverall.stream()
                .collect(Collectors.toMap(AggregatedStatistic::getId, Function.identity()));

        Map<String, List<PlayerCharacterData>> allPlayerCharacters = getAllPlayerCharacters(allStats);
        Map<AggregatedStatisticId, AggregatedStatistic> overallAggregatedData = aggregateOverallStatistics(allPlayerCharacters, gameVersion, existingStatsMapOverall);
        saveAggregatedStatistics(overallAggregatedData.values());
    }

    private Map<String, PlayerCharacterData> identifyPlayerMainCharacters(List<Object[]> stats) {
        Map<String, PlayerCharacterData> playerDataMap = new HashMap<>();
        for (Object[] row : stats) {
            String playerId = (String) row[0];
            String characterId = (String) row[1];
            int danRank = ((Number) row[2]).intValue();
            int wins = ((Number) row[3]).intValue();
            int losses = ((Number) row[4]).intValue();
            int totalPlays = wins + losses;

            PlayerCharacterData currentData = playerDataMap.get(playerId);
            if (currentData == null || totalPlays > currentData.getTotalPlays()) {
                playerDataMap.put(playerId, new PlayerCharacterData(characterId, danRank, wins, losses, totalPlays));
            }
        }
        return playerDataMap;
    }

    private Map<String, List<PlayerCharacterData>> getAllPlayerCharacters(List<Object[]> stats) {
        Map<String, List<PlayerCharacterData>> playerDataMap = new HashMap<>();
        for (Object[] row : stats)
        {
            String playerId = (String) row[0];
            String characterId = (String) row[1];
            int danRank = ((Number) row[2]).intValue();
            int wins = ((Number) row[3]).intValue();
            int losses = ((Number) row[4]).intValue();
            int totalPlays = wins + losses;

            playerDataMap.computeIfAbsent(playerId, k -> new ArrayList<>())
                    .add(new PlayerCharacterData(characterId, danRank, wins, losses, totalPlays));
        }
        return playerDataMap;
    }

    private Map<AggregatedStatisticId, AggregatedStatistic> aggregateStatistics(
            Map<String, PlayerCharacterData> playerCharacters,
            String category,
            int gameVersion,
            Map<AggregatedStatisticId, AggregatedStatistic> existingStats)
    {

        Map<AggregatedStatisticId, AggregatedStatistic> aggregatedData = new HashMap<>();
        Map<AggregatedStatisticId, Set<String>> playersPerStat = new HashMap<>();

        for (Map.Entry<String, PlayerCharacterData> entry : playerCharacters.entrySet())
        {
            String playerId = entry.getKey();
            PlayerCharacterData data = entry.getValue();

            AggregatedStatisticId id = new AggregatedStatisticId(gameVersion, data.getCharacterId(), data.getDanRank(), category);
            AggregatedStatistic stat = existingStats.get(id);
            if (stat == null)
            {
                stat = new AggregatedStatistic(id);
                stat.setComputedAt(LocalDateTime.now());
            }
            else
            {
                // Reset counts if first time processing this stat in this run
                if (!aggregatedData.containsKey(id)) {
                    stat.setTotalWins(0);
                    stat.setTotalLosses(0);
                    stat.setTotalPlayers(0);
                    stat.setTotalReplays(0);
                    stat.setComputedAt(LocalDateTime.now());
                }
            }

            // Update statistics
            stat.setTotalWins(stat.getTotalWins() + data.getWins());
            stat.setTotalLosses(stat.getTotalLosses() + data.getLosses());
            stat.setTotalReplays(stat.getTotalReplays() + data.getTotalPlays());

            // Track unique players
            playersPerStat.computeIfAbsent(id, k -> new HashSet<>()).add(playerId);

            aggregatedData.put(id, stat);
        }

        // Update totalPlayers based on unique player counts
        for (Map.Entry<AggregatedStatisticId, AggregatedStatistic> entry : aggregatedData.entrySet()) {
            AggregatedStatisticId id = entry.getKey();
            AggregatedStatistic stat = entry.getValue();
            Set<String> players = playersPerStat.get(id);
            stat.setTotalPlayers(players.size());
        }

        return aggregatedData;
    }

    private Map<AggregatedStatisticId, AggregatedStatistic> aggregateOverallStatistics(
            Map<String, List<PlayerCharacterData>> allPlayerCharacters,
            int gameVersion,
            Map<AggregatedStatisticId, AggregatedStatistic> existingStats) {

        Map<AggregatedStatisticId, AggregatedStatistic> overallData = new HashMap<>();
        Map<AggregatedStatisticId, Set<String>> playersPerStat = new HashMap<>();

        for (Map.Entry<String, List<PlayerCharacterData>> entry : allPlayerCharacters.entrySet()) {
            String playerId = entry.getKey();
            List<PlayerCharacterData> playerCharacters = entry.getValue();

            for (PlayerCharacterData data : playerCharacters) {
                AggregatedStatisticId id = new AggregatedStatisticId(gameVersion, data.getCharacterId(), data.getDanRank(), "overall");
                AggregatedStatistic stat = existingStats.get(id);
                if (stat == null) {
                    stat = new AggregatedStatistic(id);
                    stat.setComputedAt(LocalDateTime.now());
                } else {
                    // Reset counts if first time processing this stat in this run
                    if (!overallData.containsKey(id)) {
                        stat.setTotalWins(0);
                        stat.setTotalLosses(0);
                        stat.setTotalPlayers(0);
                        stat.setTotalReplays(0);
                        stat.setComputedAt(LocalDateTime.now());
                    }
                }

                // Update statistics
                stat.setTotalWins(stat.getTotalWins() + data.getWins());
                stat.setTotalLosses(stat.getTotalLosses() + data.getLosses());
                stat.setTotalReplays(stat.getTotalReplays() + data.getTotalPlays());

                // Track unique players
                playersPerStat.computeIfAbsent(id, k -> new HashSet<>()).add(playerId);

                overallData.put(id, stat);
            }
        }

        // Update totalPlayers based on unique player counts
        for (Map.Entry<AggregatedStatisticId, AggregatedStatistic> entry : overallData.entrySet()) {
            AggregatedStatisticId id = entry.getKey();
            AggregatedStatistic stat = entry.getValue();
            Set<String> players = playersPerStat.get(id);
            stat.setTotalPlayers(players.size());
        }

        return overallData;
    }

    private void saveAggregatedStatistics(Collection<AggregatedStatistic> statistics) {
        aggregatedStatisticsRepository.saveAll(statistics);
    }
}