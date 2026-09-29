// SPDX-License-Identifier: MIT
/**
 * Memory's use cases (docs/design.md 5): the event log fed by the other contexts, the memory worker (idle and
 * nightly passes: extraction and reconciliation, episodes, the profile rewrite, decay, retention), and what the
 * app does with memory (browse, edit, pin, forget, export). Other contexts reach memory only through
 * {@code port.in}; memory reaches the world only through {@code port.out}.
 */
package marvin.host.application.memory;
