package com.log_to_kot.maniacmod.client.chat;

import dev.shaurmalib.common.chat.ChatEntry;
import dev.shaurmalib.common.chat.ChatFormatEngine;
import dev.shaurmalib.forge.chat.ChatEntryRenderer;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

/**
 * Верстка повідомлення для режиму «Маньяк»: 2D-голова гравця, праворуч
 * від неї нік, пофарбований кольором своєї групи (виживші/маньяки/глядачі/
 * лобі), і текст повідомлення рядком нижче.
 *
 * <p><b>Чому це тут, а не в бібліотеці:</b> вигляд повідомлення — рішення
 * КОНКРЕТНОГО режиму. Бібліотека лише дає структуровані дані
 * ({@link ChatEntry}: нік, канал, колір групи, «сирий» текст) і SPI
 * {@link ChatEntryRenderer}; цей клас — реалізація того вигляду, який
 * потрібен маніяку. Інший мод реєструє свій рендер і отримує свій формат
 * (напр. звичайний {@code Нік: текст} або взагалі без голови), не чіпаючи
 * бібліотеку.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacChatEntryRenderer implements ChatEntryRenderer {

    private static final int HEAD_SIZE = 11;
    private static final int HEAD_GAP = 6;
    private static final int LINE_SPACING = 2;
    /** Додатковий просвіт між ніком і текстом повідомлення. */
    private static final int NAME_GAP = 1;
    private static final int NAME_COLOR_FALLBACK = ManiacUiTheme.TEXT_TITLE;
    /** Текст повідомлення гравця. */
    private static final int TEXT_PLAYER = ManiacUiTheme.TEXT_TITLE;
    /** Системні/смертні повідомлення — трохи приглушені, щоб не змагатись із репліками гравців. */
    private static final int TEXT_SYSTEM = 0xFFC9CDD3;

    @Override
    public int contentHeight(ChatEntry entry, Font font, int maxWidth) {
        if (!entry.isPlayerMessage()) {
            return Math.max(1, font.split(message(entry), maxWidth).size()) * (font.lineHeight + LINE_SPACING);
        }
        int textWidth = Math.max(1, maxWidth - HEAD_SIZE - HEAD_GAP);
        List<FormattedCharSequence> lines = font.split(rawText(entry), textWidth);
        int textBlock = font.lineHeight + NAME_GAP + LINE_SPACING
            + Math.max(1, lines.size()) * (font.lineHeight + LINE_SPACING) - LINE_SPACING;
        return Math.max(HEAD_SIZE, textBlock);
    }

    @Override
    public void render(GuiGraphics graphics, ChatEntry entry, Font font,
                       int x, int y, int width, int contentHeight, int accentArgb) {
        if (!entry.isPlayerMessage()) {
            int textY = y;
            for (FormattedCharSequence line : font.split(message(entry), width)) {
                graphics.drawString(font, line, x, textY, TEXT_SYSTEM, true);
                textY += font.lineHeight + LINE_SPACING;
            }
            return;
        }

        drawPlayerHead(graphics, entry.senderUuid(), x, y);

        int textX = x + HEAD_SIZE + HEAD_GAP;
        int textWidth = Math.max(1, width - HEAD_SIZE - HEAD_GAP);
        int nameColor = ChatFormatEngine.parseColorOrDefault(entry.colorHex(), NAME_COLOR_FALLBACK);
        graphics.drawString(font, entry.senderName(), textX, y, nameColor, true);

        int lineY = y + font.lineHeight + NAME_GAP + LINE_SPACING;
        for (FormattedCharSequence line : font.split(rawText(entry), textWidth)) {
            graphics.drawString(font, line, textX, lineY, TEXT_PLAYER, true);
            lineY += font.lineHeight + LINE_SPACING;
        }
    }

    /** 2D-голова: скін онлайн-гравця, інакше дефолтний скін за UUID. */
    private void drawPlayerHead(GuiGraphics graphics, UUID uuid, int x, int y) {
        // Тонка рамка навколо голови — у тому ж кольорі, що й рамки панелей мода.
        graphics.fill(x - 1, y - 1, x + HEAD_SIZE + 1, y + HEAD_SIZE + 1, ManiacUiTheme.BORDER_BRIGHT);
        PlayerFaceRenderer.draw(graphics, skinOf(uuid), x, y, HEAD_SIZE);
    }

    private ResourceLocation skinOf(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        if (uuid != null && minecraft.level != null) {
            Player player = minecraft.level.getPlayerByUUID(uuid);
            if (player instanceof AbstractClientPlayer clientPlayer) {
                return clientPlayer.getSkinTextureLocation();
            }
        }
        return uuid == null ? DefaultPlayerSkin.getDefaultSkin() : DefaultPlayerSkin.getDefaultSkin(uuid);
    }

    private static Component message(ChatEntry entry) {
        return entry.message() == null ? Component.empty() : entry.message();
    }

    private static Component rawText(ChatEntry entry) {
        return Component.literal(entry.rawText() == null ? "" : entry.rawText());
    }
}
