package me.foesio.foCoinflip.api;

/** Current cached net money won, lost, and their difference; disk writes may still be queued. */
public record CoinflipStats(double totalWon, double totalLost, double totalSum) {
}
