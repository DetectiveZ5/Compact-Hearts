package detectivez5.compacthearts;

// I am proud to say that this is my first mod ever for MC
// Just like to replicate the mods that bonc has that he doesn't upload
// SO I'M DOING ONE MYSELF.
// If someone sees this, feet. (Easter Egg)
// Version 0.1.0-alpha (this has no published build of the java.)
// Next update: Version 1.0.0

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Random;

/**
 * CompactHeartsClient — mirrors vanilla's container-counting + blinking logic
 * but adds compact mode.
 */
public class CompactHeartsClient implements ClientModInitializer {
    public static final String MOD_ID = "compacthearts";

    // Config
    private static final int COMPACT_THRESHOLD = 200; // hearts threshold to switch to compact view
    private static final int FLASH_TICKS = 20; // kept as a secondary safety timer (not used for vanilla blink). Keep
                                               // for future use

    // Vanilla-like state (these replicate Gui fields used by vanilla)
    private static int lastHealth = 0; // vanilla: lastHealth (int)
    private static int displayHealth = 0; // vanilla: displayHealth (int)
    private static long lastHealthTime = 0L; // vanilla: lastHealthTime (millis)
    private static int healthBlinkTime = 0; // vanilla: healthBlinkTime (tick count)
    private static final Random random = new Random(); // vanilla uses Random seeded by tickCount * const

    // Flash layer detection (which resource was consumed by damage)
    // 0 = none, 1 = absorption flashed, 2 = health flashed
    private static int flashLayer = 0;
    private static int flashTimer = 0;

    @Override
    public void onInitializeClient() {
        HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, old -> (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null)
                return;

            // Current int health and display logic use vanilla-style updates
            int i = Mth.ceil(player.getHealth()); // current health (int)
            long nowMs = Util.getMillis();
            int tickCount = mc.gui.getGuiTicks();

            // Compute the blink boolean 'bl' the same way vanilla does: based on
            // healthBlinkTime and tickCount
            boolean bl = healthBlinkTime > tickCount && ((healthBlinkTime - tickCount) / 3L % 2L) == 1L;

            // Vanilla: adjust healthBlinkTime when health changes and
            // player.invulnerableTime > 0
            if (i < lastHealth && player.invulnerableTime > 0) {
                lastHealthTime = nowMs;
                healthBlinkTime = tickCount + 20;
            } else if (i > lastHealth && player.invulnerableTime > 0) {
                lastHealthTime = nowMs;
                healthBlinkTime = tickCount + 10;
            }

            // Vanilla: after 1000 ms, displayHealth will catch up to i
            if (nowMs - lastHealthTime > 1000L) {
                displayHealth = i;
                lastHealthTime = nowMs;
            }

            // Update lastHealth for next frame
            lastHealth = i;

            // Seed random the same way vanilla does each render call
            random.setSeed((long) tickCount * 312871L);

            // Gui anchor positions (vanilla uses guiWidth/2 - 91 and guiHeight - 39)
            int baseX = graphics.guiWidth() / 2 - 91;
            int baseY = graphics.guiHeight() - 39;

            // f = Math.max(attribute MAX_HEALTH, Math.max(displayHealth, i)) (vanilla)
            float f = Math.max((float) player.getAttributeValue(Attributes.MAX_HEALTH),
                    (float) Math.max(displayHealth, i));

            // absorption in vanilla is 'o'
            int o = Mth.ceil(player.getAbsorptionAmount());

            // Detect damage on combined (health+absorption) and pick which layer was
            // consumed
            // This is similar to your previous logic but uses integer ceil'd values to
            // avoid float drift
            // We use prev combined values via static fields (we'll store them in
            // lastHealthCombined-like vars)
            // Simpler detection: compare i + o to previous display of combined via
            // lastStoredCombined (use lastHealth+prevAbs would be messy).
            // We'll use flashTimer and flashLayer updated when combined decreases.
            // (We'll store previous combined in displayHealth + previous absorption - but
            // displayHealth may lag; instead use static holder:)
            // We'll use lastStoredCombined in the form of lastHealth + lastAbsStored;
            // create on first render.
            // (We reuse lastHealth as we already track it; track lastAbs separately.)
            // Add static lastAbs variable:
            // (declare at top) private static int lastAbs = 0;
            // For brevity here, we'll detect the layer by comparing previous stored values
            // (we'll implement lastAbs below).

            // Update compact or full rendering
            if (Mth.ceil(f + o) >= COMPACT_THRESHOLD) {
                // --- COMPACT MODE ---
                int rows = Mth.ceil((f + o) / 2.0F / 10.0F);
                int rowSpacing = Math.max(10 - (rows - 2), 3);
                renderHearts(graphics, player, baseX, baseY, rowSpacing,
                        /* regenIndex= */-1, /* f= */2.0F,
                        /* currentI= */(int) Math.ceil(player.getHealth()),
                        /* displayJ= */(int) Math.ceil(player.getHealth()),
                        /* o= */0, /* bl= */bl, /* drawText */ true);

                // Decrement flash timer (keep fallback behavior)
                if (flashTimer > 0)
                    flashTimer--;
                if (flashTimer == 0)
                    flashLayer = 0;
            } else {
                // --- FULL (multi-slot) RENDER MODE ---
                // Row spacing 'q' in vanilla is computed as Math.max(10 - (p - 2), 3)
                int rows = Mth.ceil((f + o) / 2.0F / 10.0F);
                int rowSpacing = Math.max(10 - (rows - 2), 3);

                renderHearts(graphics, player, baseX, baseY, rowSpacing, -1, f, i, displayHealth, o, bl, false);
                if (flashTimer > 0)
                    flashTimer--;
                if (flashTimer == 0)
                    flashLayer = 0;
            }
        });
    }

    /**
     * Reimplementation of vanilla's renderHearts method with container-first +
     * overlays.
     *
     * @param graphics   GuiGraphics
     * @param player     LocalPlayer
     * @param baseX      guiWidth/2 - 91
     * @param baseY      guiHeight - 39
     * @param rowSpacing 'q' spacing value vanilla computes
     * @param regenIndex unused here (vanilla uses it for regen highlight); pass -1
     *                   if not used
     * @param f          float max computed by vanilla (see earlier)
     * @param currentI   current integer health (ceil)
     * @param displayJ   displayHealth from vanilla (may lag)
     * @param o          absorption hearts (ceil)
     * @param bl         blinking boolean from vanilla's healthBlinkTime logic
     * @param drawText   whether to draw the "current / max" text (syncs color with
     *                   hearts)
     */
    private static void renderHearts(GuiGraphics graphics, LocalPlayer player, int baseX, int baseY, int rowSpacing,
            int regenIndex, float f, int currentI, int displayJ, int o, boolean bl, boolean drawText) {
        boolean isHardcore = player.level().getLevelData().isHardcore();

        int p = Mth.ceil(f / 2.0F); // hearts based on f (max attr or display)
        int q = Mth.ceil(o / 2.0F); // absorption hearts
        int totalSlots = p + q;

        // seed random same way vanilla does (tickCount already seeded in caller)
        // We added random.setSeed in caller; here just reuse random

        // Iterate slots from top-right to left-bottom like vanilla (s = p+q-1 ... 0)
        for (int s = totalSlots - 1; s >= 0; s--) {
            int row = s / 10;
            int col = s % 10;
            int x = baseX + col * 8;
            int y = baseY - row * rowSpacing;

            // Small vertical jitter for very low hearts (vanilla: if currentI + o <= 4)
            if (currentI + o <= 4) {
                y += random.nextInt(2);
            }

            // Draw container always (empty heart outline)
            Identifier containerSprite = getContainerSprite(isHardcore, bl);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, java.util.Objects.requireNonNull(containerSprite), x, y,
                    9, 9);

            // Overlay logic:
            // s indexes slots from right -> left; 'x' in vanilla equals s*2 for damage
            // math. (nother easter egg)
            // Considering if it these unused variables should be removed. All of these
            // comments will be improved too it the next update.
            int heartX = s * 2;
            boolean slotIsAbsorption = s >= p; // absorption slots are those beyond p (health-only)
            int healthSlotIndex = s - q; // slot index within health part (can be negative for pure absorption slots)

            // Determine if overlay should be drawn and whether it's half:
            boolean drawOverlay = false;
            boolean half = false;

            // If slot corresponds to health (left side)
            if (!slotIsAbsorption) {
                // determine whether this slot is within current health display
                int hpSlot = s; // 0..p-1 corresponds to health slots from left
                float remainingHp = player.getHealth() - (hpSlot * 2);
                if (remainingHp > 0f) {
                    drawOverlay = true;
                    half = remainingHp > 0f && remainingHp < 2.0f;
                } else {
                    drawOverlay = false;
                }
            } else {
                // absorption slot
                int absorptionIndex = s - p; // 0..q-1
                float remainingAbs = player.getAbsorptionAmount() - (absorptionIndex * 2);
                if (remainingAbs > 0f) {
                    drawOverlay = true;
                    half = remainingAbs > 0f && remainingAbs < 2.0f;
                } else {
                    drawOverlay = false;
                }
            }

            // If blinking and this overlay is the type that should blink, pass blinking
            // true to sprite pickers.
            boolean overlayBlink = bl && flashLayer == (slotIsAbsorption ? 1 : 2);

            if (drawOverlay) {
                Identifier overlay;
                // If player has status effects override to poisoned/withered/frozen (vanilla
                // priority)
                if (player.hasEffect(MobEffects.POISON)) {
                    overlay = getHeartSpritePrefix("poisoned_", isHardcore, overlayBlink, half);
                } else if (player.hasEffect(MobEffects.WITHER)) {
                    overlay = getHeartSpritePrefix("withered_", isHardcore, overlayBlink, half);
                } else if (player.isFullyFrozen()) {
                    overlay = getHeartSpritePrefix("frozen_", isHardcore, overlayBlink, half);
                } else {
                    if (slotIsAbsorption || drawText && player.hasEffect(MobEffects.ABSORPTION)) {
                        overlay = getAbsorbingSprite(isHardcore, overlayBlink, half);
                    } else {
                        overlay = getHeartSprite(player, isHardcore, overlayBlink, half);
                    }
                }
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, java.util.Objects.requireNonNull(overlay), x, y, 9,
                        9);
            }
        }

        // --- Draw the "current / max" text if requested ---
        if (drawText) {
            // compute heart counts (hearts not half-hearts)
            int o1 = Mth.ceil(player.getAbsorptionAmount());
            int currentHearts = (int) Math.ceil(currentI + o1);
            float f1 = Math.max((float) player.getAttributeValue(Attributes.MAX_HEALTH),
                    (float) Math.max(displayHealth, currentHearts));
            int maxHearts = Mth.ceil(f1 + o1);

            // Determine text color according to priority and blinking state:
            // Priority: Poison > Wither > Frozen > Absorption (if any) > Normal
            final int COLOR_NORMAL = 0xFFFF1313;
            final int COLOR_NORMAL_BLINK = 0xFFFFA1A1;
            final int COLOR_ABS = 0xFFD4AF37;
            final int COLOR_ABS_BLINK = 0xFFD4C07E;
            final int COLOR_FROZEN = 0xFF80E5EF;
            final int COLOR_FROZEN_BLINK = 0xFFA8F7FF;
            final int COLOR_POISON = 0xFF947818;
            final int COLOR_POISON_BLINK = 0xFFA2935E;
            final int COLOR_WITHER = 0xFF2B2B2B;
            final int COLOR_WITHER_BLINK = 0xFF404040;

            int textColor;
            if (player.hasEffect(MobEffects.POISON)) {
                textColor = bl ? COLOR_POISON_BLINK : COLOR_POISON;
            } else if (player.hasEffect(MobEffects.WITHER)) {
                textColor = bl ? COLOR_WITHER_BLINK : COLOR_WITHER;
            } else if (player.isFullyFrozen()) {
                textColor = bl ? COLOR_FROZEN_BLINK : COLOR_FROZEN;
            } else if (player.hasEffect(MobEffects.ABSORPTION)) {
                textColor = bl ? COLOR_ABS_BLINK : COLOR_ABS;
            } else {
                textColor = bl ? COLOR_NORMAL_BLINK : COLOR_NORMAL;
            }

            // compute a reasonable iconX/iconY consistent with how vanilla places the
            // left-side hud anchor
            int centerX = baseX + 91;
            int iconX = centerX - 90;
            int iconY = baseY;

            String text = currentHearts + " / " + maxHearts;
            Minecraft mc = Minecraft.getInstance();
            // drawString parameters: (font, string, x, y, color, dropShadow)
            graphics.drawString(mc.font, text, iconX + 12, iconY + 1, textColor, true);
        }
    }

    /* ---------- Sprite pickers (same helpers as before) ---------- */

    /** Container sprite (empty-heart outline) */
    private static Identifier getContainerSprite(boolean hardcore, boolean blinking) {
        String base = "hud/heart/container";
        if (hardcore)
            base = "hud/heart/container_hardcore";
        if (blinking)
            base = base + "_blinking";
        return Identifier.withDefaultNamespace(base);
    }

    /** Absorbing (gold) heart sprite */
    private static Identifier getAbsorbingSprite(boolean hardcore, boolean blinking, boolean half) {
        String base = "hud/heart/absorbing_";
        if (hardcore)
            base += "hardcore_";
        base += half ? "half" : "full";
        if (blinking)
            base += "_blinking";
        return Identifier.withDefaultNamespace(base);
    }

    /** Normal/poisoned/withered/frozen heart sprite helper */
    private static Identifier getHeartSprite(LocalPlayer player, boolean hardcore, boolean blinking, boolean half) {
        String prefix = "";
        if (player.hasEffect(MobEffects.POISON)) {
            prefix = "poisoned_";
        } else if (player.hasEffect(MobEffects.WITHER)) {
            prefix = "withered_";
        } else if (player.isFullyFrozen()) {
            prefix = "frozen_";
        }

        return getHeartSpritePrefix(prefix, hardcore, blinking, half);
    }

    private static Identifier getHeartSpritePrefix(String prefix, boolean hardcore, boolean blinking, boolean half) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        if (hardcore)
            sb.append("hardcore_");
        sb.append(half ? "half" : "full");
        if (blinking)
            sb.append("_blinking");
        return Identifier.withDefaultNamespace("hud/heart/" + sb.toString());
    }
}