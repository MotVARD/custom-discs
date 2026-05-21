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

public class UploadScreen extends Screen {
    private static final int CHUNK_SIZE = 256 * 1024;

    private EditBox titleEdit;
    private File selectedFile;
    private volatile String status;
    private volatile boolean uploading = false;
    private volatile boolean waitingForServer = false;
    private volatile float progress = 0.0f;

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

        uploading = true;
        progress = 0.0f;
        Thread t = new Thread(this::uploadTask, "CustomDiscs-Upload");
        t.setDaemon(true);
        t.start();
    }

    private void uploadTask() {
        try {
            File f = selectedFile;
            String fileName = f.getName();
            String title = titleEdit.getValue();
            long size = f.length();

            if (size <= 0 || size > Integer.MAX_VALUE) {
                status = Component.translatable("customdiscs.status.invalid_size").getString();
                return;
            }
            int totalSize = (int) size;

            CustomDiscsMod.LOGGER.info("[Upload] Starting upload {} ({} bytes)", fileName, totalSize);

            byte[] buffer = new byte[CHUNK_SIZE];
            int offset = 0;
            try (InputStream in = new BufferedInputStream(new FileInputStream(f), CHUNK_SIZE)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    byte[] chunk = (n == buffer.length) ? buffer : Arrays.copyOf(buffer, n);
                    PacketDistributor.sendToServer(new UploadChunkPacket(fileName, title, chunk, offset, totalSize));
                    offset += n;
                    progress = Math.min(0.95f, (float) offset / totalSize);
                    status = Component.translatable("customdiscs.status.uploading",
                            (int) (progress * 100)).getString();
                    Thread.sleep(10);
                }
            }

            uploading = false;
            waitingForServer = true;
            progress = 0.99f;
            status = Component.translatable("customdiscs.status.waiting_server").getString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = Component.translatable("customdiscs.status.upload_interrupted").getString();
            uploading = false;
            waitingForServer = false;
        } catch (IOException e) {
            status = Component.translatable("customdiscs.status.upload_error", e.getMessage()).getString();
            CustomDiscsMod.LOGGER.error("[Upload] Failure: {}", e.getMessage());
            uploading = false;
            waitingForServer = false;
        }
    }

    public void onServerResponse(boolean success, String message) {
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
