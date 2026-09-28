// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

/**
 * The 16-byte datagram header, without its constant magic and version.
 *
 * @param type the message type byte (0..255), known or not
 * @param seq  the sender's sequence number, unsigned 32 bits
 * @param tUs  the sender's clock, microseconds since boot (unsigned 64 bits; fits a long for 292 000 years)
 */
public record Header(int type, long seq, long tUs) {
}
