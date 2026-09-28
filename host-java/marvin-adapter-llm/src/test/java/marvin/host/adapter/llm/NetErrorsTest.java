// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

import org.junit.jupiter.api.Test;

class NetErrorsTest {

    @Test
    void aRefusedConnectionIsSaidSoWhateverWrapsIt() {
        Throwable jdk = new IOException(new ConnectException());
        jdk.getCause().initCause(new ClosedChannelException());
        assertThat(NetErrors.reason(jdk)).isEqualTo("Connection refused");
        assertThat(NetErrors.reason(new RuntimeException("I/O error", jdk))).isEqualTo("Connection refused");
    }

    @Test
    void otherwiseTheDeepestMessageOrTheClass() {
        assertThat(NetErrors.reason(new IOException("outer", new IOException("no route to host")))).isEqualTo("no route to host");
        assertThat(NetErrors.reason(new IOException("outer", new ClosedChannelException()))).isEqualTo("outer");
        assertThat(NetErrors.reason(new ClosedChannelException())).isEqualTo("ClosedChannelException");
    }
}
