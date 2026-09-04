package com.truetileanimationmovement;

import net.runelite.client.RuneLite;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

/**
 * Append-only debug logging into the .runelite directory, always off the
 * client thread. Debug helpers must never perform disk I/O on the client
 * thread, or the act of measuring a frame hitch causes one.
 */
final class DebugFileLogger
{
    // A frame taking longer than this could not be normal presentation work.
    static final int STALL_TRACE_FRAME_GAP_THRESHOLD_MILLIS = 60;
    // Any single plugin stage taking longer than this is worth investigating.
    static final int STALL_TRACE_STAGE_THRESHOLD_MILLIS = 40;
    static final String STALL_TRACE_LOG_FILE = "tma-stall-trace.log";

    private DebugFileLogger()
    {
    }

    static void Append(String FileName, String Message)
    {
        CompletableFuture.runAsync(() ->
        {
            try
            {
                Path LogFile = RuneLite.RUNELITE_DIR.toPath().resolve(FileName);
                String Line = System.currentTimeMillis() + " " + Message +
                        System.lineSeparator();
                Files.write(
                        LogFile,
                        Line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
            catch (Exception ignored)
            {
                // Debug-only helper; never surface file I/O failures in-game.
            }
        });
    }
}
