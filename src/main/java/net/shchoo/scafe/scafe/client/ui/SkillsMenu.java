package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.ProductLine;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The Skills tab. A list, one row a skill:
 * <pre>
 * $ [ 64 / 100] . skill0  [          64600/66800]
 * </pre>
 * (its symbol, level, a bullet in its type's colour, its name -- "+N" when prestiged -- and a green bar filling up
 * to the next level). Up/Down pick, Enter opens the skill's page: the same row, its type and who found it, its xp
 * across prestiges, then its recipes, navigated as before (Up/Down a line, Left/Right a tier, Enter a waypoint,
 * m marks every node of it). Tab steps through the selected recipe's components; Enter on one opens the page of
 * the skill that makes it, on that recipe -- Esc comes back the way you went. Products and components are in
 * the colour of the skill making them. At Lv. 100, p prestiges it (after a yes). Esc goes back.
 */
public class SkillsMenu extends Menu {
    private static final int PRODUCT_LINE_HEIGHT = 3;

    private int index = 0;
    /** On the selected skill's page (else the list). */
    private boolean open = false;
    private int lineIndex = 0;
    /** Which recipe (tier) of the selected product line; Enter sets a waypoint to the nearest node for it. */
    private int tierIndex = 0;
    /** p pressed at Lv. 100: asking "Prestige?". */
    private boolean confirmingPrestige;
    /** Tab: which of the selected recipe's components is picked (-1: none, the tiers are). */
    private int componentIndex = -1;
    /** A tier's right-click menu (Set / clear waypoint, Mark / unmark nodes), or null. */
    private PopupMenu tierPopup;
    private int tierPopupX;
    private int tierPopupY;
    /** This frame's board, for making what's drawn clickable. */
    private Board board;
    /** The pages you came through by opening components (skill, line, tier), for Esc to go back along. */
    private final java.util.Deque<int[]> trail = new java.util.ArrayDeque<>();

    public SkillsMenu() {
        super("Skills");
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return !board.skills.isEmpty();
    }

    @Override
    protected void onFocus(Player player, Board board) {
        index = 0;
        open = false;
        confirmingPrestige = false;
        componentIndex = -1;
        trail.clear();
    }

    /** On a skill's page. */
    public boolean skillOpen() {
        return open;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        Skill skill = board.skills.get(index);
        if (tierPopup != null && tierPopup.isOpen()) {
            tierPopup.key(key);
            return Result.HANDLED;
        }
        if (open) {
            return pageKey(key, player, board, skill);
        }
        switch (key.getKeyType()) {
            case ArrowUp -> {
                if (index == 0) return Result.RELEASE;
                index--;
            }
            case ArrowDown -> index = Math.min(board.skills.size() - 1, index + 1);
            case Enter -> {
                open = true;
                lineIndex = 0;
                tierIndex = 0;
                componentIndex = -1;
                confirmingPrestige = false;
                trail.clear();
            }
            case Escape -> {
                return Result.RELEASE;
            }
            default -> {
                return Result.IGNORED;
            }
        }
        return Result.HANDLED;
    }

    /** The selected recipe's components, in order (none on a page without recipes). */
    private List<Product> components(Skill skill) {
        return skill.productLines.isEmpty() ? List.of() : List.copyOf(selectedRecipe(skill).components.keySet());
    }

    /** Enter on a component: to the page of the skill that makes it, on its recipe (remembering where you were). */
    private void openComponent(Player player, Board board, Product component) {
        Recipe recipe = player.content.madeBy(component);
        if (recipe == null || !board.skills.contains(recipe.skill)) {
            return;
        }
        trail.push(new int[]{index, lineIndex, tierIndex});
        index = board.skills.indexOf(recipe.skill);
        for (int line = 0; line < recipe.skill.productLines.size(); line++) {
            int tier = recipe.skill.productLines.get(line).recipes.indexOf(recipe);
            if (tier >= 0) {
                lineIndex = line;
                tierIndex = tier;
            }
        }
        componentIndex = -1;
    }

    private Result pageKey(KeyStroke key, Player player, Board board, Skill skill) {
        if (confirmingPrestige) {
            confirmingPrestige = false;
            if (key.getKeyType() == KeyType.Character && Character.toLowerCase(key.getCharacter()) == 'y') {
                player.prestige(skill);
            }
            return Result.HANDLED;
        }
        boolean recipes = !skill.productLines.isEmpty();
        if (key.getKeyType() != KeyType.Tab && key.getKeyType() != KeyType.Enter && key.getKeyType() != KeyType.Escape) {
            componentIndex = -1;
        }
        switch (key.getKeyType()) {
            case ArrowUp -> lineIndex = Math.max(0, lineIndex - 1);
            case ArrowDown -> lineIndex = recipes ? Math.min(skill.productLines.size() - 1, lineIndex + 1) : 0;
            case ArrowLeft -> tierIndex = Math.max(0, tierIndex - 1);
            case ArrowRight -> tierIndex++;
            case Tab -> {
                // through the components, then back to the tiers
                int count = components(skill).size();
                componentIndex = count == 0 || componentIndex + 1 >= count ? -1 : componentIndex + 1;
            }
            case Enter -> {
                if (componentIndex >= 0) {
                    openComponent(player, board, components(skill).get(componentIndex));
                } else if (recipes) {
                    player.waypoints.toggle(selectedRecipe(skill), player);
                }
            }
            case Escape -> {
                if (componentIndex >= 0) {
                    componentIndex = -1;
                } else if (!trail.isEmpty()) {
                    int[] back = trail.pop();
                    index = back[0];
                    lineIndex = back[1];
                    tierIndex = back[2];
                } else {
                    open = false;
                }
            }
            case Character -> {
                char c = key.getCharacter();
                if (c == 'm' && recipes) {
                    player.toggleHighlighting(selectedRecipe(skill));
                } else if (c == 'p' && canPrestige(player, skill)) {
                    confirmingPrestige = true;
                } else {
                    return Result.IGNORED;
                }
            }
            default -> {
                return Result.IGNORED;
            }
        }
        Skill now = board.skills.get(index);
        if (!now.productLines.isEmpty()) {
            tierIndex = Math.max(0, Math.min(now.productLines.get(lineIndex).recipes.size() - 1, tierIndex));
        }
        return Result.HANDLED;
    }

    private static boolean canPrestige(Player player, Skill skill) {
        return skill.type != SkillType.COMBAT && player.getLevel(skill) >= Levels.MAX_LEVEL;
    }

    public Recipe selectedRecipe(Skill skill) {
        return skill.productLines.get(lineIndex).recipes.get(tierIndex);
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        this.board = board;
        if (board.skills.isEmpty()) {
            return;
        }
        if (isFocused() && open) {
            drawPage(screen, player, board.skills.get(index), nameWidth(player, board));
        } else {
            drawList(screen, player, board);
        }
    }

    /** The name column: wide enough for every skill's name and prestige mark. */
    private static int nameWidth(Player player, Board board) {
        return board.skills.stream().mapToInt(s -> name(player, s).length()).max().orElse(6);
    }

    private static String name(Player player, Skill skill) {
        int prestige = player.getPrestige(skill);
        return skill.name + (prestige > 0 ? " +" + prestige : "");
    }

    private void drawList(Screen screen, Player player, Board board) {
        int start = isFocused() ? Math.max(0, Math.min(index - rows() / 2, board.skills.size() - (rows() - 1))) : 0;
        int nameWidth = nameWidth(player, board);
        for (int i = start; i < Math.min(board.skills.size(), start + rows() - 1); i++) {
            int y = firstRow() + (i - start);
            boolean selected = isFocused() && i == index;
            if (selected) {
                TextGraphics g = screen.newTextGraphics();
                g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
                g.setCharacter(textX() - 1, y, '>');
            }
            drawRow(screen, player, board.skills.get(i), y, nameWidth, selected);
            int pick = i;
            board.clicks.option(Clicks.BASE, textX(), y, textWidth(), 1, (button, cx, cy) -> {
                claim(player, board);
                index = pick;
                open = true;
                lineIndex = 0;
                tierIndex = 0;
                componentIndex = -1;
                confirmingPrestige = false;
                trail.clear();
            });
        }
        TextGraphics g = screen.newTextGraphics();
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX(), hintRow(), fit(isFocused() ? "[Up/Down] pick  [Enter] open  [Esc] back" : "[Down] browse skills",
                textWidth()));
    }

    /**
     * One skill: {@code $ [ 64 / 100] . skill0  [   64600/66800]} -- symbol, level, a bullet in its type's colour,
     * name (and prestige), and a green bar of the way to the next level with the xp right-aligned in it.
     */
    private void drawRow(Screen screen, Player player, Skill skill, int y, int nameWidth, boolean selected) {
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(SkillStyle.getColorForSkill(skill));
        g.setCharacter(textX(), y, SkillStyle.getSymbolForSkill(skill));
        int level = player.getLevel(skill);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        if (selected) {
            g.enableModifiers(SGR.BOLD);
        }
        g.putString(textX() + 2, y, String.format(Locale.ROOT, "[%3d / %d]", level, Levels.MAX_LEVEL));
        g.disableModifiers(SGR.BOLD);
        g.setForegroundColor(typeColor(skill.type));
        g.setCharacter(textX() + 14, y, '\u2022');
        int x = textX() + 16;
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        if (selected) {
            g.enableModifiers(SGR.BOLD);
        }
        g.putString(x, y, skill.name);
        g.disableModifiers(SGR.BOLD);
        int prestige = player.getPrestige(skill);
        if (prestige > 0) {
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            g.putString(x + skill.name.length(), y, " +" + prestige);
        }
        int barX = x + nameWidth + 1;
        drawBar(screen, player, skill, barX, y, textX() + textWidth() - barX);
    }

    /** "[  64600/66800]" with the inside filled green as far as this level has got (all of it at the top). */
    private static void drawBar(Screen screen, Player player, Skill skill, int x, int y, int width) {
        if (width < 4) {
            return;
        }
        int level = player.getLevel(skill);
        long xp = player.getExperience(skill);
        boolean max = level >= Levels.MAX_LEVEL;
        long from = Skill.XP_PER_LEVEL[Math.max(0, level - 1)], to = max ? from : player.getExperienceToNextLevel(skill);
        double fraction = max ? 1 : to <= from ? 0 : Math.max(0, Math.min(1, (double) (xp - from) / (to - from)));
        String text = max ? (skill.type == SkillType.COMBAT ? "MAX" : "MAX - p on its page to prestige") : xp + "/" + to;
        int inner = width - 2;
        if (text.length() > inner) {
            text = max ? "MAX" : ShortNumbers.of(xp) + "/" + ShortNumbers.of(to);
        }
        int filled = (int) Math.round(fraction * inner);
        screen.setCharacter(x, y, TextCharacter.fromCharacter('[', TextColor.ANSI.BLACK_BRIGHT, TextColor.ANSI.DEFAULT)[0]);
        for (int i = 0; i < inner; i++) {
            int t = i - (inner - text.length());
            char c = t >= 0 && t < text.length() ? text.charAt(t) : ' ';
            TextColor bg = i < filled ? TextColor.ANSI.GREEN : TextColor.ANSI.DEFAULT;
            screen.setCharacter(x + 1 + i, y, TextCharacter.fromCharacter(c, TextColor.ANSI.WHITE_BRIGHT, bg)[0]);
        }
        screen.setCharacter(x + 1 + inner, y, TextCharacter.fromCharacter(']', TextColor.ANSI.BLACK_BRIGHT, TextColor.ANSI.DEFAULT)[0]);
    }

    /** The bullet after the level: green primary, blue secondary, red tertiary, white combat. */
    static TextColor typeColor(SkillType type) {
        return switch (type) {
            case PRIMARY -> TextColor.ANSI.GREEN_BRIGHT;
            case SECONDARY -> TextColor.ANSI.BLUE_BRIGHT;
            case TERTIARY -> TextColor.ANSI.RED_BRIGHT;
            case COMBAT -> TextColor.ANSI.WHITE_BRIGHT;
        };
    }

    /** A skill's page: its row again, type, who found it and when, xp across prestiges, then its recipes. */
    private void drawPage(Screen screen, Player player, Skill skill, int nameWidth) {
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int y = firstRow();
        drawRow(screen, player, skill, y++, nameWidth, true);
        ContentBatch.Origin origin = player.content.origin(skill.id);
        g.setForegroundColor(typeColor(skill.type));
        g.putString(textX(), y, skill.type.name());
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX() + skill.type.name().length() + 2, y++, fit(origin == null ? "found by ? (before this was kept)"
                : "found by " + origin.by() + " on " + date(origin.at()), textWidth() - skill.type.name().length() - 2));
        int prestige = player.getPrestige(skill);
        long lifetime = player.lifetimeExperience(skill);
        int virtual = Levels.virtualLevelFor(lifetime);
        g.setForegroundColor(prestige > 0 ? TextColor.ANSI.YELLOW_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
        // the virtual level: what the total xp would make it with no cap (shown once it's past what the row says)
        g.putString(textX(), y++, fit(String.format(Locale.ROOT, "%,d total xp", lifetime)
                + (prestige > 0 ? "  [prestige " + prestige + "]" : "")
                + (virtual > player.getLevel(skill) ? "  virtual Lv. " + virtual : ""), textWidth()));
        if (confirmingPrestige) {
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            g.enableModifiers(SGR.BOLD);
            String ask = fit("Prestige " + skill.name + "? Back to Lv. 1, items get more valuable [y/n]", textWidth());
            g.putString(textX(), y, ask);
            g.disableModifiers(SGR.BOLD);
            int yn = ask.indexOf("[y/n]");
            if (yn >= 0) {
                board.clicks.option(Clicks.BASE, textX() + yn + 1, y, 1, 1, (button, cx, cy) -> {
                    confirmingPrestige = false;
                    player.prestige(skill);
                });
                board.clicks.option(Clicks.BASE, textX() + yn + 3, y, 1, 1, (button, cx, cy) -> confirmingPrestige = false);
            }
        } else if (canPrestige(player, skill)) {
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            g.putString(textX(), y, fit("[p] prestige: revert to Lv. 1?",
                    textWidth()));
            board.clicks.option(Clicks.BASE, textX(), y, 30, 1, (button, cx, cy) -> {
                claim(player, board);
                confirmingPrestige = true;
            });
        }
        y++;
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.enableModifiers(SGR.BOLD);
        g.putString(textX(), y++, "Recipes");
        g.disableModifiers(SGR.BOLD);
        drawRecipes(screen, player, skill, y);

        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX(), hintRow() - 1, fit(componentIndex >= 0 ? "[Tab] next component  [Enter] open its skill"
                : "[Up/Down] line  [Left/Right] tier  [Enter] waypoint  [Tab] components", textWidth()));
        g.putString(textX(), hintRow(), fit("[m] mark nodes  " + (canPrestige(player, skill) ? "[p] prestige  " : "")
                + (trail.isEmpty() ? "[Esc] back to skills" : "[Esc] back"), textWidth()));
    }

    private void drawRecipes(Screen screen, Player player, Skill skill, int top) {
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        if (skill.productLines.isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(textX(), top, "none (trained by fighting)");
            return;
        }
        int width = textWidth();
        int fits = Math.max(1, (hintRow() - 2 - top) / PRODUCT_LINE_HEIGHT);
        int first = Math.max(0, lineIndex - (fits - 1));
        int y = top;
        for (int i = first; i < Math.min(skill.productLines.size(), first + fits); i++) {
            ProductLine productLine = skill.productLines.get(i);
            Recipe base = productLine.recipes.get(0);
            boolean selected = i == lineIndex;
            if (selected) {
                g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
                g.setCharacter(textX() - 1, y, '>');
                g.enableModifiers(SGR.BOLD);
            }
            g.setForegroundColor(SkillStyle.getColorForSkill(skill));
            g.putString(textX(), y, fit(base.product.lineName(), width));
            g.disableModifiers(SGR.BOLD);

            int x = textX() + 2;
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(x, y + 1, "Lv");
            x += 3;
            for (int t = 0; t < productLine.recipes.size(); t++) {
                Recipe recipe = productLine.recipes.get(t);
                if (x + 3 > textX() + width) {
                    g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                    g.putString(x - 1, y + 1, "~");
                    break;
                }
                boolean unlocked = player.getLevel(skill) >= recipe.levelNeeded;
                boolean isWaypoint = player.waypoints.has(recipe);
                boolean here = selected && t == tierIndex;
                g.setForegroundColor(isWaypoint ? TextColor.ANSI.MAGENTA_BRIGHT
                        : unlocked ? TextColor.ANSI.GREEN_BRIGHT : here ? TextColor.ANSI.WHITE : TextColor.ANSI.BLACK_BRIGHT);
                g.setBackgroundColor(here ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
                g.putString(x, y + 1, String.valueOf(recipe.levelNeeded));
                int line = i, tier = t, tx = x, ty = y + 1;
                board.clicks.option(Clicks.BASE, x, y + 1, String.valueOf(recipe.levelNeeded).length(), 1,
                        (button, cx, cy) -> clickTier(skill, line, tier, button, tx, ty, player));
                g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                x += 3;
            }
            if (selected) {
                g.setForegroundColor(SkillStyle.getColorForSkill(skill));
                g.putString(x + 1, y + 1, fit(selectedRecipe(skill).product.name, Math.max(0, textX() + width - x - 1)));
            }
            int componentsY = y + 2;
            drawComponents(g, player, selected ? selectedRecipe(skill) : base, textX() + 2, componentsY, textX() + width,
                    selected ? componentIndex : -1, (component, at, length) -> board.clicks.option(Clicks.BASE, at, componentsY,
                            length, 1, (button, cx, cy) -> {
                                claim(player, board);
                                openComponent(player, board, component);
                            }));
            y += PRODUCT_LINE_HEIGHT;
        }
    }

    /**
     * "from 2x skill0{0}, skill1{0}" -- each component in the colour of the skill making it; the picked one ({@code
     * picked}, -1 none) on grey. The selected line names the selected tier's own components, so Tab + Enter goes
     * to exactly what it uses.
     */
    /** Where a component's name was drawn (so it can be clicked). */
    interface Drawn {
        void at(Product component, int x, int length);
    }

    private static void drawComponents(TextGraphics g, Player player, Recipe recipe, int x, int y, int right, int picked,
                                       Drawn drawn) {
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(x, y, "from ");
        x += 5;
        if (recipe.components.isEmpty()) {
            g.putString(x, y, "raw");
            return;
        }
        int i = 0;
        for (var c : recipe.components.entrySet()) {
            String text = (i > 0 ? ", " : "") + (c.getValue() > 1 ? c.getValue() + "x " : "");
            String name = picked == -1 ? c.getKey().lineName() : c.getKey().name;
            if (x + text.length() + name.length() > right) {
                g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                g.putString(Math.min(x, right - 1), y, "~");
                return;
            }
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(x, y, text);
            x += text.length();
            Recipe makes = player.content.madeBy(c.getKey());
            g.setForegroundColor(makes != null ? SkillStyle.getColorForSkill(makes.skill) : TextColor.ANSI.DEFAULT);
            g.setBackgroundColor(i == picked ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
            if (i == picked) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(x, y, name);
            g.disableModifiers(SGR.BOLD);
            drawn.at(c.getKey(), x, name.length());
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            x += name.length();
            i++;
        }
    }

    /**
     * A tier's level clicked: it's selected; left toggles its waypoint, right opens Set / clear waypoint, Mark /
     * unmark all nodes, Cancel just under it.
     */
    private void clickTier(Skill skill, int line, int tier, Clicks.Button button, int x, int y, Player player) {
        claim(player, board);
        lineIndex = line;
        tierIndex = tier;
        componentIndex = -1;
        Recipe recipe = selectedRecipe(skill);
        if (button == Clicks.Button.LEFT) {
            player.waypoints.toggle(recipe, player);
            return;
        }
        tierPopup = new PopupMenu(recipe.product.name, SkillStyle.getColorForSkill(skill), List.of(
                PopupMenu.Option.of(player.waypoints.has(recipe) ? "Clear waypoint" : "Set waypoint",
                        () -> player.waypoints.toggle(recipe, player)),
                PopupMenu.Option.of(player.highlightedRecipes.contains(recipe) ? "Unmark nodes" : "Mark all nodes",
                        () -> player.toggleHighlighting(recipe))));
        tierPopupX = x;
        tierPopupY = y + 1;
    }

    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        if (tierPopup != null && tierPopup.isOpen()) {
            tierPopup.draw(screen, tierPopupX, tierPopupY, board.clicks);
        } else {
            tierPopup = null;
        }
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** A creation time in this computer's time zone. */
    static String date(long epochMillis) {
        return WHEN.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
