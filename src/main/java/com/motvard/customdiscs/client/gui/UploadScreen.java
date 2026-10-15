package com.motvard.customdiscs.client.gui;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.network.UploadChunkPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public class UploadScreen extends Screen {
    private static final int CHUNK_SIZE = 256 * 1024;

    private EditBox titleEdit;
    private volatile File selectedFile;
    private volatile String status;
    private volatile boolean uploading = false;
    private volatile boolean waitingForServer = false;
    private volatile long waitingSince = 0L;
    private volatile float progress = 0.0f;

    /**
     * One upload attempt, from the first chunk until the server answers it. The sending thread
     * keeps going when the screen is closed, so this lives outside the screen instance.
     */
    private static final class Attempt {
        /** Set when the server answers (success, deduplicated or rejected): stop sending. */
        volatile boolean replied;
        volatile long lastActivityMs;

        Attempt(long now) {
            this.lastActivityMs = now;
        }
    }

    /**
     * The client's only running upload. Replies carry no upload id, so a second upload is not
     * started while one is unanswered: every reply then belongs to this attempt.
     */
    private static final AtomicReference<Attempt> CURRENT = new AtomicReference<>();

    /** Called on the client thread for every {@code UploadCompletePacket}. */
    public static void markServerReplied() {
        Attempt attempt = CURRENT.getAndSet(null);
        if (attempt != null) attempt.replied = true;
    }

    private static void release(Attempt attempt) {
        CURRENT.compareAndSet(attempt, null);
    }

    private volatile Attempt attempt;

    public UploadScreen() {
        super(Component.translatable("customdiscs.screen.upload"));
        this.status = Component.translatable("customdiscs.status.select_file").getString();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.titleEdit = new EditBox(this.font, centerX - 100, centerY - 40, 200, 20,
                Component.translatable("customdiscs.upload.title_hint"));
        this.titleEdit.setHint(Component.translatable("customdiscs.upload.title_hint"));
        this.addRenderableWidget(this.titleEdit);

        this.addRenderableWidget(Button.builder(
                Component.translatable("customdiscs.gui.choose_file"),
                btn -> chooseFile())
                .bounds(centerX - 100, centerY - 10, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("customdiscs.gui.upload"),
                btn -> startUpload())
                .bounds(centerX - 100, centerY + 20, 200, 20).build());
    }

    private void chooseFile() {
        Thread t = new Thread(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(3)
                        .put(stack.UTF8("*.wav"))
                        .put(stack.UTF8("*.mp3"))
                        .put(stack.UTF8("*.ogg"))
                        .flip();

                String result = TinyFileDialogs.tinyfd_openFileDialog(
                        Component.translatable("customdiscs.dialog.select_music").getString(),
                        null,
                        filters,
                        Component.translatable("customdiscs.dialog.music_files").getString(),
                        false
                );

                if (result != null) {
                    File f = new File(result);
                    long size = f.length();
                    int maxSize = Config.SERVER.maxUploadBytes.get();
                    if (size > maxSize) {
                        this.status = Component.translatable("customdiscs.error.file_too_large_client",
                                size / 1024 / 1024, maxSize / 1024 / 1024).getString();
                        return;
                    }
                    this.selectedFile = f;
                    this.status = Component.translatable("customdiscs.status.file_selected",
                            f.getName(), formatSize(size)).getString();
                    if (this.titleEdit.getValue().isEmpty()) {
                        String name = f.getName().replaceAll("(?i)\\.(wav|mp3|ogg)$", "");
                        this.titleEdit.setValue(name);
                    }
                }
            } catch (Exception e) {
                this.status = Component.translatable("customdiscs.status.picker_error", e.getMessage()).getString();
            }
        }, "CustomDiscs-FilePicker");
        t.setDaemon(true);
        t.start();
    }

    private void startUpload() {
        if (selectedFile == null || !selectedFile.exists()) {
            status = Component.translatable("customdiscs.status.no_file").getString();
            return;
        }
        if (uploading || waitingForServer) return;

        long now = System.currentTimeMillis();
        long timeoutMs = Config.CLIENT.packetDownloadTimeoutSeconds.get() * 1000L;
        Attempt previous = CURRENT.get();
        if (previous != null && now - previous.lastActivityMs > timeoutMs) {
            release(previous); // its reply was lost
        }
        Attempt started = new Attempt(now);
        if (!CURRENT.compareAndSet(null, started)) {
            // An upload started from an earlier (closed) upload screen is still running.
            status = Component.translatable("customdiscs.status.upload_busy").getString();
            return;
        }
        attempt = started;
        uploading = true;
        progress = 0.0f;
        Thread t = new Thread(() -> uploadTask(started), "CustomDiscs-Upload");
        t.setDaemon(true);
        t.start();
    }

    private void uploadTask(Attempt attempt) {
        try {
            File f = selectedFile;
            String fileName = f.getName();
            String title = titleEdit.getValue();
            long size = f.length();

            if (size <= 0 || size > Integer.MAX_VALUE) {
                status = Component.translatable("customdiscs.status.invalid_size").getString();
                uploading = false; // otherwise the Upload button stays locked
                release(attempt);
                return;
            }
            int totalSize = (int) size;

            CustomDiscsMod.LOGGER.debug("[Upload] Starting upload {} ({} bytes)", fileName, totalSize);

            byte[] buffer = new byte[CHUNK_SIZE];
            int offset = 0;
            try (InputStream in = new BufferedInputStream(new FileInputStream(f), CHUNK_SIZE)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    // The server already answered (rejected, or the file was already there):
                    // sending the rest would only be thrown away.
                    if (attempt.replied) break;
                    // Always copy: the packet is serialised asynchronously, so the next read()
                    // must not overwrite the bytes in flight.
                    byte[] chunk = Arrays.copyOf(buffer, n);
                    PacketDistributor.sendToServer(new UploadChunkPacket(fileName, title, chunk, offset, totalSize));
                    offset += n;
                    attempt.lastActivityMs = System.currentTimeMillis();
                    reportProgress(attempt, Math.min(0.95f, (float) offset / totalSize));
                    Thread.sleep(10);
                }
            }

            finishSending(attempt);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = Component.translatable("customdiscs.status.upload_interrupted").getString();
            uploading = false;
            waitingForServer = false;
            release(attempt);
        } catch (IOException e) {
            status = Component.translatable("customdiscs.status.upload_error", e.getMessage()).getString();
            CustomDiscsMod.LOGGER.error("[Upload] Failure: {}", e.getMessage());
            uploading = false;
            waitingForServer = false;
            release(attempt);
        }
    }

    /** Progress from the sending thread; never overwrites the server's answer once it arrived. */
    private synchronized void reportProgress(Attempt attempt, float value) {
        if (attempt.replied) return;
        progress = value;
        status = Component.translatable("customdiscs.status.uploading", (int) (value * 100)).getString();
    }

    /** All chunks sent (or sending stopped early): wait for the server unless it already replied. */
    private synchronized void finishSending(Attempt attempt) {
        uploading = false;
        if (attempt.replied) return; // onServerResponse has set (or is about to set) the final status
        // Set the timestamp before flipping the flag so the render thread never sees
        // waitingForServer == true with a stale waitingSince (spurious instant timeout).
        waitingSince = System.currentTimeMillis();
        waitingForServer = true;
        progress = 0.99f;
        status = Component.translatable("customdiscs.status.waiting_server").getString();
    }

    public synchronized void onServerResponse(boolean success, String message) {
        waitingForServer = false;
        if (success) {
            progress = 1.0f;
            status = Component.translatable("customdiscs.status.upload_complete").getString();
        } else {
            progress = 0.0f;
            status = Component.translatable("customdiscs.status.upload_error", message).getString();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / 1024 / 1024) + " MB";
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Don't wait forever if the server never confirms (e.g. the final chunk was dropped).
        if (waitingForServer) {
            long timeoutMs = Config.CLIENT.packetDownloadTimeoutSeconds.get() * 1000L;
            if (System.currentTimeMillis() - waitingSince > timeoutMs) {
                waitingForServer = false;
                Attempt timedOut = attempt;
                if (timedOut != null) release(timedOut);
                progress = 0.0f;
                status = Component.translatable("customdiscs.status.upload_timeout").getString();
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, this.status, this.width / 2, this.height / 2 + 50, 0xAAAAAA);

        if (uploading || waitingForServer) {
            int barWidth = 200;
            int barHeight = 10;
            int x = this.width / 2 - barWidth / 2;
            int y = this.height / 2 + 70;
            guiGraphics.fill(x, y, x + barWidth, y + barHeight, 0xFF444444);
            guiGraphics.fill(x, y, x + (int) (barWidth * progress), y + barHeight, 0xFF00FF00);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
