// SPDX-License-Identifier: MIT
/**
 * The soul's use cases (docs/design.md 8): the owner's edits of the core and the learned part, the weekly reflection that
 * proposes a learned part, the owner's decision on a proposal, reverting to any version, and the soul's text for the
 * system prompt. Other contexts reach it through {@code port.in}; it reads the week and asks the model through
 * {@code port.out}.
 */
package marvin.host.application.soul;
