// SPDX-License-Identifier: MIT
package marvin.host.app.archfixture.application.conversation;

import marvin.host.app.archfixture.application.memory.RecallService;

/** Fixture: the conversation calling memory's service directly, which the context rule must catch. */
public class Answering {
    public String answer(RecallService memory) {
        return memory.recall("what");
    }
}
