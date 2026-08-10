package com.billy65536.qab;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令层路径解析与自动补全工具。
 *
 * <p>约定：文件名参数统一使用 {@link com.mojang.brigadier.arguments.StringArgumentType#string()}。
 * 当补全项的 basename 含有会导致 string() 把整段拆成多 token 的字符（空格，以及命令解析的
 * 保留字符如 {@code " ' ( ) { } [ ] | < > ＼ `} 等）时，补全项整体用双引号包裹，使玩家选中后
 * 能被 string() 原样当作单个参数接收，从而可直接使用。
 *
 * <p>对应的 {@link #resolveFile} 在按名拼路径前会先剥掉首尾双引号，因此经补全带引号传入的
 * 文件名也能正确落位到真实文件。
 */
public final class CommandPathHelper {

    private CommandPathHelper() {
    }

    /**
     * 会导致 {@code string()} 参数中断、因此补全时必须加引号的字符集合。
     * 包含空格与命令语法的常见保留字符。
     */
    private static final String QUOTE_TRIGGERS = " \t\n\r\"'(){}[]|<>`\\";

    /** 判断给定 basename 是否需要在补全时加引号。 */
    private static boolean needsQuote(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            if (QUOTE_TRIGGERS.indexOf(name.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 剥掉首尾成对的双引号（来自补全或玩家手输），其余内容原样保留。 */
    private static String stripQuotes(String name) {
        String s = name.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 解析文件：先尝试 {@code dir/name}，再尝试 {@code dir/name.ext}（对每个 ext），
     * 最后按全局路径 {@code Path.of(name)}；返回首个存在的常规文件，否则 {@code null}。
     * 传入的 name 会先剥去首尾双引号，以兼容经补全带引号传入的情况。
     */
    public static Path resolveFile(Path dir, String name, String... exts) {
        String raw = stripQuotes(name);
        if (raw.isEmpty()) return null;

        Path directInDir = dir.resolve(raw);
        if (Files.isRegularFile(directInDir)) return directInDir;

        for (String ext : exts) {
            Path candidate = dir.resolve(raw + ext);
            if (Files.isRegularFile(candidate)) return candidate;
        }

        Path global = Path.of(raw);
        if (Files.isRegularFile(global)) return global;

        return null;
    }

    /**
     * 返回 dir 下匹配任一扩展名（含点）的常规文件 basename 的自动补全提供器。
     * 返回的 basename 已去掉扩展名；若含会导致 string() 中断的字符，整体用双引号包裹。
     */
    public static SuggestionProvider<FabricClientCommandSource> suggestBasenames(Path dir, String... exts) {
        return (CommandContext<FabricClientCommandSource> ctx,
                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) -> {
            List<String> names = new ArrayList<>();
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) {
                        if (!Files.isRegularFile(p)) continue;
                        String fileName = p.getFileName().toString();
                        String base = stripKnownExtension(fileName, exts);
                        if (base == null) continue;
                        names.add(needsQuote(base) ? "\"" + base + "\"" : base);
                    }
                } catch (IOException ignored) {
                    // 目录读取失败时静默返回空补全
                }
            }
            return CommandSource.suggestMatching(names, builder);
        };
    }

    /** 若 fileName 以某个已知扩展名结尾，去掉并返回 basename；否则返回 null。 */
    private static String stripKnownExtension(String fileName, String... exts) {
        String lower = fileName.toLowerCase();
        for (String ext : exts) {
            String lowExt = ext.toLowerCase();
            if (lower.endsWith(lowExt)) {
                return fileName.substring(0, fileName.length() - ext.length());
            }
        }
        return null;
    }
}
