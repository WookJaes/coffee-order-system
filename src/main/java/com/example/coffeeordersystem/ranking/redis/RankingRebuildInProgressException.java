package com.example.coffeeordersystem.ranking.redis;

public class RankingRebuildInProgressException extends IllegalStateException {

	public RankingRebuildInProgressException() {
		super("랭킹 Redis 재구성 중입니다.");
	}
}
