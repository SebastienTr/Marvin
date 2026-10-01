// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.tools;

import java.util.Map;
import java.util.Objects;

import marvin.host.application.vision.port.in.Camera;
import marvin.host.domain.conversation.ImageAttachment;
import marvin.host.domain.conversation.tool.Look;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.vision.event.Frame;

/**
 * {@code look}: one picture with the camera the settings choose (the vision context's), checked and stripped of any
 * metadata as an image shown in Talk is, then described by the model for images. The model reads the description;
 * the picture is dropped when the description is done.
 */
public final class LookTool implements ToolFunction {

    /** Describes a picture (the model for images), answering the person's question when there is one. */
    @FunctionalInterface
    public interface Eyes {
        /** @throws ToolError the model cannot see, or failed */
        String describe(ImageAttachment picture, String question);
    }

    private final Camera camera;
    private final Eyes eyes;

    public LookTool(Camera camera, Eyes eyes) {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.eyes = Objects.requireNonNull(eyes, "eyes");
    }

    /** The tool, for a registry. */
    public static ToolRegistry.Tool tool(Camera camera, Eyes eyes) {
        return new ToolRegistry.Tool(Look.spec(), new LookTool(camera, eyes));
    }

    @Override
    public Object call(Map<String, Object> arguments, Map<String, Object> context) {
        String question = arguments.get("question") instanceof String q ? q.strip() : "";
        Frame frame;
        try {
            frame = camera.take(Camera.LOOK);
        } catch (Camera.NoPicture e) {
            throw new ToolError("the camera took no picture: " + e.getMessage());
        }
        ImageAttachment picture;
        try {
            picture = ImageAttachment.of(frame.jpeg());
        } catch (ImageAttachment.InvalidImage e) {
            throw new ToolError("the camera's picture could not be read: " + e.getMessage());
        }
        String seen = eyes.describe(picture, question);
        return Look.result(frame.source(), picture.width(), picture.height(), seen);
    }
}
