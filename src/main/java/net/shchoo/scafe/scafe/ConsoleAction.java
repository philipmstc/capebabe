package net.shchoo.scafe.scafe;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public enum ConsoleAction {
    RIGHT('d', "Move right", (player, board) -> player.moveBy(1, 0)),
    LEFT('a', "Move left", (player, board) -> player.moveBy(-1, 0)),
    UP('w', "Move up", (player, board) -> player.moveBy(0, -1)),
    DOWN('s', "Move down", (player, board) -> player.moveBy(0, 1)),

    PERFORM('q', "Perform action (again to stop)", (player, board) -> player.send(new Intent.Perform())),

    GOAL('g', "Add a goal for the locked action you're standing on", (player, board) -> player.send(new Intent.AddGoal())),

    LIST('l', "List skills", (player, board, additionalInputs) -> {
        SkillType filter = getSkillType(additionalInputs);
        for (Skill s : board.skills.stream().filter(s -> s.getType().equals(filter) || filter == null).toList()) {
            GameLog.say("[" + s.type + "] Skill: " + s.name);
            for (ProductLine pl : s.productLines) {
                for (Recipe r : pl.recipes) {
                    String components = "[" + r.components.keySet().stream().map(p -> p.name).collect(Collectors.joining(",")) + "]";
                    GameLog.say("\t" + r.levelNeeded + ":" + r.product.name + components);
                }
            }
        }
    }),
    HELP('k', "Help", (player, board) -> {
        for (int i = 0; i < ConsoleAction.values().length; i++) {
            ConsoleAction ca = ConsoleAction.values()[i];
            GameLog.say(ca.input + " - " + ca.description);
        }
    }),

    DEBUG_CREATE_NEW_RANDOM_SKILL('1', "Create new random skill(s) [count]",
            (player, board, args) -> player.send(new Intent.Admin("1", args))),

    DEBUG_CREATE_RANDOM_PRODUCT_LINE('2', "Create random product line(s) [count]",
            (player, board, args) -> player.send(new Intent.Admin("2", args))),

    ECHO('e', "Echo input", (player, board, additionalInputs) -> {
        GameLog.say(additionalInputs.toString());
    }),

    DEBUG_CREATE_ACTION_NODE('3', "Create random node here [capacity] [charges] [respawn ticks] (0 = unlimited)",
            (player, board, args) -> player.send(new Intent.Admin("3", args)));

    private static SkillType getSkillType(List<String> additionalInputs) {
        SkillType filter = null;
        if (!additionalInputs.isEmpty()) {
            if (SkillType.TERTIARY.name().equalsIgnoreCase(additionalInputs.get(0))) {
                filter = SkillType.TERTIARY;
            }
            if (SkillType.SECONDARY.name().equalsIgnoreCase(additionalInputs.get(0))) {
                filter = SkillType.SECONDARY;
            }
            if (SkillType.PRIMARY.name().equalsIgnoreCase(additionalInputs.get(0))) {
                filter = SkillType.PRIMARY;
            }
        }
        return filter;
    }

    final char input;
    final OnSelect onSelect;
    final String description;

    interface OnSelect {
        void onSelect(Player player, Board board, List<String> additionalInputs);
    }

    ConsoleAction(char input, String description, BiConsumer<Player, Board> onSelect) {
        this.input = input;
        this.description = description;
        this.onSelect = (player, board, additionalInputs) -> onSelect.accept(player, board);
    }

    ConsoleAction(char input, String description, OnSelect onSelect) {
        this.input = input;
        this.description = description;
        this.onSelect = onSelect;
    }

    public void onSelect(Player player, Board board, List<String> additionalInputs) {
        onSelect.onSelect(player, board, additionalInputs);
    }

}
