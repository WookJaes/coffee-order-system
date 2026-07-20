package com.example.coffeeordersystem.ranking.redis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class RedisRankingKey {

	private static final String RANKING_PREFIX = "coffee:ranking:";
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

	private RedisRankingKey() {
	}

	public static String dailyRanking(LocalDate date) {
		return RANKING_PREFIX + DATE_FORMATTER.format(date);
	}

	public static String processedEvent(Long eventId) {
		return RANKING_PREFIX + "processed:" + eventId;
	}

	public static String dailyStatus(LocalDate date) {
		return RANKING_PREFIX + "status:" + DATE_FORMATTER.format(date);
	}

	public static String dailyProcessedOrderCount(LocalDate date) {
		return RANKING_PREFIX + "count:" + DATE_FORMATTER.format(date);
	}

	public static String rebuilding() {
		return RANKING_PREFIX + "rebuilding";
	}
}
