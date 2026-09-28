// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.Map;

/**
 * One device as the app shows it (the Python {@code UISink.devices()} entry), as of the last tick.
 *
 * @param id          {@code ip:port}
 * @param rssi        dBm, {@code null} when unknown or simulated
 * @param rssiBars    0..4, {@code null} when unknown
 * @param connectedAt wall clock, seconds since the epoch
 * @param ageS        seconds since it last sent anything
 * @param rates       per second over the last seconds: datagrams, scans, radar, vitals, audio
 * @param lossPct     datagrams lost over the same window, or {@code null} without traffic
 * @param points      points in its last lidar scan
 */
public record DeviceStatus(String id, String name, Board.Role role, String board, String firmware, String ip,
                           Integer rssi, Integer rssiBars, long uptimeS, boolean simulated, boolean camera,
                           boolean audio, boolean screen, boolean online, double ageS, double connectedAt,
                           Map<String, Double> rates, Double lossPct, LinkStats.Snapshot stats, int points) {
}
