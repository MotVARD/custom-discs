package com.motvard.customdiscs.command;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.network.OpenUploadScreenPacket;
import com.motvard.customdiscs.server.UploadAttribution;
import com.motvard.customdiscs.util.CustomDiscFiles;
import com.motvard.customdiscs.util.ModPaths;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class DiscCommand {

    /** Real uploaded files: regular files, excluding dotfiles and in-progress .partial files. */
    private static final FileFilter UPLOADS = f ->
            f.isFile() && !f.getName().startsWith(".") && !f.getName().endsWith(ModPaths.PARTIAL_SUFFIX);

    /** Tab-completion for /disc delete — suggests the names currently on the server. */
    private static final SuggestionProvider<CommandSourceStack> FILE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(uploadedFileNames(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("disc")
                .then(Commands.literal("upload")
                    .requires(src -> src.hasPermission(Config.SERVER.uploadPermLevel.get()))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        PacketDistributor.sendToPlayer(player, new OpenUploadScreenPacket());
                        return 1;
                    })
                )
                .then(Commands.literal("list")
                    .requires(src -> src.hasPermission(Config.SERVER.listPermLevel.get()))
                    .executes(DiscCommand::listFiles)
                )
                .then(Commands.literal("delete")
                    .requires(src -> src.hasPermission(Config.SERVER.deletePermLevel.get()))
                    // greedyString accepts file names containing spaces without quoting.
                    .then(Commands.argument("filename", StringArgumentType.greedyString())
                        .suggests(FILE_SUGGESTIONS)
                        .executes(ctx -> deleteFile(ctx, StringArgumentType.getString(ctx, "filename")))
                    )
                )
        );
    }

    private static File[] uploadedFiles() {
        File[] files = ModPaths.UPLOADS_DIR.toFile().listFiles(UPLOADS);
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        }
        return files;
    }

    private static List<String> uploadedFileNames() {
        File[] files = uploadedFiles();
        if (files == null) return List.of();
        return Arrays.stream(files).map(File::getName).toList();
    }

    private static int listFiles(CommandContext<CommandSourceStack> ctx) {
        File[] files = uploadedFiles();
        if (files == null || files.length == 0) {
            ctx.getSource().sendSuccess(() -> Component.translatable("customdiscs.command.list.empty"), false);
            return 0;
        }
        Map<String, String> uploaders = UploadAttribution.snapshot();
        MutableComponent msg = Component.translatable("customdiscs.command.list.header", files.length);
        for (File f : files) {
            String uploader = uploaders.getOrDefault(f.getName(), "?");
            msg.append(Component.literal(
                    "\n  " + f.getName() + " — " + formatSize(f.length()) + " — " + uploader));
        }
        ctx.getSource().sendSuccess(() -> msg, false);
        return files.length;
    }

    private static int deleteFile(CommandContext<CommandSourceStack> ctx, String rawName) {
        String safeName = CustomDiscFiles.sanitize(rawName);
        File uploadDir = ModPaths.UPLOADS_DIR.toFile();
        File file = new File(uploadDir, safeName);
        new File(uploadDir, safeName + ModPaths.PARTIAL_SUFFIX).delete();

        if (!file.exists()) {
            ctx.getSource().sendFailure(Component.translatable("customdiscs.error.file_not_found", safeName));
            return 0;
        }
        if (file.delete()) {
            UploadAttribution.remove(safeName);
            ctx.getSource().sendSuccess(() -> Component.translatable("customdiscs.success.file_deleted", safeName), true);
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.translatable("customdiscs.error.delete_failed", safeName));
            return 0;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / 1024 / 1024) + " MB";
    }
}
