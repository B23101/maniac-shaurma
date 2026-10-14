package com.log_to_kot.maniacmod.client.screen.wiki;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.intro.IntroClient;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.items.ItemRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Єдина точка входу у вікі предметів: клавіша H → {@link #tryOpen()}.
 *
 * ── Хто що вирішує ───────────────────────────────────────────────────
 * Клавішу читає {@code ClientInputHandler} (він лише повідомляє «H
 * натиснули»). ТУТ вирішується, чи відкривати: роль, кіно-вступ, предмет
 * у руці. Так {@code ClientInputHandler} лишається «клавіша → намір», а
 * правила вікі живуть в одному місці поруч із самим екраном.
 *
 * ── Лише для виживих ─────────────────────────────────────────────────
 * Маньяк і глядач не відкривають нічого й не бачать підказки. Перевірка
 * суто клієнтська й косметична: вікі нічого не змінює в матчі й не шле
 * жодного пакета, тож серверної копії цього правила не потрібно.
 *
 * ── Який предмет вважається «вікі-предметом» ─────────────────────────
 * Будь-який предмет мода з {@link ItemRegistry} у групі
 * {@link ItemRegistry.Group#SURVIVOR}. Додали предмет у реєстр → він
 * автоматично відкривається по H (текст беремо з lang за id, див.
 * {@code ItemWikiScreen}); окремого списку вікі-предметів нема, щоб
 * його не довелося синхронізувати вручну.
 */
@OnlyIn(Dist.CLIENT)
public final class ItemWikiOpener {

    private ItemWikiOpener() {}

    /** Відкриває вікі предмета з руки (спершу головна, потім друга), якщо це можливо. */
    public static void tryOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null) return;                       // поверх іншого меню — ні
        if (!ClientMatchState.isSurvivor()) return;          // лише виживі
        if (IntroClient.blocksScreens()) return;             // кіно-вступ блокує екрани

        ItemStack stack = mc.player.getMainHandItem();
        String id = wikiIdOf(stack);
        if (id == null) {
            stack = mc.player.getOffhandItem();
            id = wikiIdOf(stack);
        }
        if (id == null) {
            // Нічого, про що є стаття: підказуємо в рядку над хотбаром, а не мовчимо.
            mc.gui.setOverlayMessage(Component.translatable("maniacmod.wiki.nothing_held"), false);
            UiAnim.playMenuWarn();
            return;
        }
        mc.setScreen(new ItemWikiScreen(id, stack.copy()));
    }

    /**
     * Id вікі-запису для стека або {@code null}, якщо стаття про цей предмет
     * не передбачена (порожня рука, чужий предмет, предмет маньяка).
     */
    public static String wikiIdOf(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null || !ManiacMod.MOD_ID.equals(key.getNamespace())) return null;
        ItemRegistry.Entry entry = ItemRegistry.all().get(key.getPath());
        if (entry == null || entry.group() != ItemRegistry.Group.SURVIVOR) return null;
        return entry.id();
    }
}
