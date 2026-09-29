// SPDX-License-Identifier: MIT
/**
 * Memory (docs/design.md 5): the append-only event log, bi-temporal facts with their provenance, day, week and
 * month episodes, the profile block and its versions, and the rules that consolidate them (reconciliation,
 * decay, retention, the profile's size limit). Plain Java: the model calls, the embeddings and the storage are
 * behind the application's ports.
 */
package marvin.host.domain.memory;
