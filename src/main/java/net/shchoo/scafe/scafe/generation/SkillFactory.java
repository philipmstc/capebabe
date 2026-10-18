package net.shchoo.scafe.scafe.generation;

import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.WorldContent;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.shchoo.scafe.scafe.model.SkillType.*;

public class SkillFactory {

    private static final double TARGET_PRIMARY_SKILL_DENSITY = 1;
    private static final double TARGET_SECONDARY_SKILL_DENSITY = 3;
    private static final double TARGET_TERTIARY_SKILL_DENSITY = 1;

    private static final double TARGET_PRODUCT_LINE_DENSITY_PRIMARY = 1;
    private static final double TARGET_PRODUCT_LINE_DENSITY_SECONDARY = 1;
    private static final double TARGET_PRODUCT_LINE_DENSITY_TERTIARY = 1;
    private static final double TOLERANCE = 0.33;

    static class WeightedSkillTypeChoice {
        SkillType type;
        double weight;

        WeightedSkillTypeChoice(
                SkillType type,
                double weight
        ) {
            this.type = type;
            this.weight = weight;
        }
    }

    private static final Map<SkillType, List<Character>> SYMBOLS_BY_TYPE = Map.of(
            PRIMARY, List.of('#', '%', '!'),
            SECONDARY, List.of('$', '?', '='),
            TERTIARY, List.of('@', '&', '+'));

    private static WeightedSkillTypeChoice typeInNeedOfLove(Collection<Skill> existingSkills,
                                                            Random rng,
                                                            double primaryDensity,
                                                            double secondaryDensity,
                                                            double tertiaryDensity,
                                                            Function<Collection<Skill>, Integer> actual) {

        double densitySum = primaryDensity + secondaryDensity + tertiaryDensity;
        Collection<Skill> primarySkills = existingSkills.stream().filter(s -> PRIMARY.equals(s.type)).toList();
        long actualPrimarySkills = actual.apply(primarySkills);
        Collection<Skill> secondarySkills = existingSkills.stream().filter(s -> SkillType.SECONDARY.equals(s.type)).toList();
        long actualSecondarySkills = actual.apply(secondarySkills);
        Collection<Skill> tertiarySkills = existingSkills.stream().filter(s -> TERTIARY.equals(s.type)).toList();
        long actualTertiarySkills = actual.apply(tertiarySkills);

        double primaryWeight = primaryDensity / densitySum;
        double secondaryWeight = secondaryDensity / densitySum;
        double tertiaryWeight = tertiaryDensity / densitySum;

        double primaryDiff = ((double) actualPrimarySkills / actual.apply(existingSkills)) / primaryWeight; // should be 1
        double secondaryDiff = ((double) actualSecondarySkills / actual.apply(existingSkills)) / secondaryWeight; // should be 1
        double tertiaryDiff = ((double) actualTertiarySkills / actual.apply(existingSkills)) / tertiaryWeight; // should be 1

        double normPrimaryWeight = primaryWeight * (densitySum - primaryDiff);
        double normSecondaryWeight = secondaryWeight * (densitySum - secondaryDiff);
        double normTertiaryWeight = tertiaryWeight * (densitySum - tertiaryDiff);

        System.out.println(String.format("Diffs: [%.2f, %.2f, %.2f] -- Counts: [%d, %d, %d]",
                primaryDiff, secondaryDiff, tertiaryDiff,
                actualPrimarySkills, actualSecondarySkills, actualTertiarySkills));
        System.out.println(String.format("Nums:  [%.2f, %.2f, %.2f] -- Weights: [%.2f, %.2f, %.2f]",
                normPrimaryWeight,
                normSecondaryWeight,
                normTertiaryWeight,
                primaryWeight,
                secondaryWeight,
                tertiaryWeight));

        double normalizedSum = normPrimaryWeight + normSecondaryWeight + normTertiaryWeight;
        normPrimaryWeight = normPrimaryWeight / normalizedSum;
        normSecondaryWeight = normSecondaryWeight / normalizedSum;
        normTertiaryWeight = normTertiaryWeight / normalizedSum;

        System.out.println(String.format("norms: [%.2f, %.2f, %.2f]",
                normPrimaryWeight,
                normSecondaryWeight,
                normTertiaryWeight));

        SkillType type;
        double weight;

        if (existingSkills.size() < 2) {
            type = PRIMARY;
            weight = -1;
        } else {
            double random = rng.nextDouble();
            if (random < normPrimaryWeight) {
                type = PRIMARY;
                weight = normPrimaryWeight;
            } else if (actualSecondarySkills == 0 || random < normPrimaryWeight + normSecondaryWeight) {
                type = SkillType.SECONDARY;
                weight = normSecondaryWeight;
            } else {
                type = TERTIARY;
                weight = normTertiaryWeight;
            }
        }
        return new WeightedSkillTypeChoice(type, weight);
    }

    public static Skill createRandomSkill(WorldContent world, Random rng) {
        List<Skill> crafting = world.craftingSkills();
        SkillType type = typeInNeedOfLove(
                crafting,
                rng,
                TARGET_PRIMARY_SKILL_DENSITY,
                TARGET_SECONDARY_SKILL_DENSITY,
                TARGET_TERTIARY_SKILL_DENSITY,
                Collection::size)
                .type;
        return createSkill(world, rng, "skill" + crafting.size(), type);
    }

    /** Creates the skill with its product lines and adds it to {@code world}. */
    public static Skill createSkill(WorldContent world, Random rng, String name, SkillType type) {
        long sameType = world.skills.stream().filter(s -> s.type == type).count();
        Skill skill = new Skill(world.allocateId(), name, type, world.skills.size(),
                SYMBOLS_BY_TYPE.get(type).get((int) (sameType % 3)));
        do {
            addNewProductLineToSkill(skill, world);
        } while (needsNewProductLine(skill, world.craftingSkills(), rng));
        world.addSkill(skill);
        return skill;
    }

    public static void addNewProductLineToSkill(Skill skill, WorldContent world) {
        String name = skill.name + "{" + (skill.productLines.size()) + "}";
        List<Skill> allSkills = world.skills;
        switch (skill.type) {
            case PRIMARY -> skill.productLines.add(ProductLineFactory.createPrimaryProductLine(world, skill, name));
            case SECONDARY ->
                    skill.productLines.add(ProductLineFactory.createSecondaryProductLine(world, skill, name, allSkills.stream()
                            .filter(s -> PRIMARY.equals(s.type)).toList()));
            case TERTIARY ->
                    skill.productLines.add(ProductLineFactory.createTertiaryProductLine(world, skill, name, allSkills.stream()
                            .filter(s -> SkillType.SECONDARY.equals(s.type)).toList()));
        }
    }

    public static boolean correctProductLineDensity = true;

    private static boolean needsNewProductLine(Skill target, Collection<Skill> allSkills, Random rng) {
        if (!correctProductLineDensity) {
            return false;
        }
        Collection<Skill> all = new ArrayList<>(allSkills);
        all.add(target);

        WeightedSkillTypeChoice choice = typeInNeedOfLove(all,
                rng,
                TARGET_PRODUCT_LINE_DENSITY_PRIMARY,
                TARGET_PRODUCT_LINE_DENSITY_SECONDARY,
                TARGET_PRODUCT_LINE_DENSITY_TERTIARY,
                skills -> skills.stream().flatMap(s -> s.productLines.stream()).toList().size());

        return choice.weight > TOLERANCE && choice.type == target.type;
    }
}
