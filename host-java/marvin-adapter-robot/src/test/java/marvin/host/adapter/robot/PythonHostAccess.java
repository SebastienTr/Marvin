// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.io.IOException;

/** {@link PythonHost} for the tests of other packages. */
public final class PythonHostAccess {
    private PythonHostAccess() {
    }

    public static String run(String code) throws IOException, InterruptedException {
        return PythonHost.run(code);
    }
}
