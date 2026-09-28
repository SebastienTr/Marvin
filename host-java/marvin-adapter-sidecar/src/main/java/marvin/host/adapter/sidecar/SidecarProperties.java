// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code marvin.sidecar.*}: the Python processes the host starts.
 *
 * @param python    the Python to run them with ({@code $MARVIN_PYTHON}; empty: host/.venv, then python3)
 * @param repo      the repository, whose {@code host/} holds the Python host ({@code $MARVIN_REPO}; empty: the
 *                  first parent of the working directory that has it)
 * @param simulator in demo mode, start the Python simulator ({@code marvin-host sim}) as the robot
 * @param seed      in demo mode, import a simulated past week made by the Python host's demo code
 * @param voice     start the voice sidecar ({@code python -m marvin_host.sidecar.voice}) with the host
 * @param voiceArgs more arguments for it (its test mode: {@code --fake --say SECONDS:TEXT})
 */
@ConfigurationProperties("marvin.sidecar")
public record SidecarProperties(String python, Path repo, boolean simulator, boolean seed,
                                @DefaultValue("true") boolean voice, List<String> voiceArgs) {

    public SidecarProperties {
        voiceArgs = voiceArgs == null ? List.of() : voiceArgs.stream().filter(a -> !a.isBlank()).toList();
    }
}
