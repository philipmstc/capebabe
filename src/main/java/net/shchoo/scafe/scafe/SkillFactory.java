package net.shchoo.scafe.scafe;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.shchoo.scafe.scafe.SkillType.*;

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

    private static WeightedSkillTypeChoice typeInNeedOfLove(Collection<Skill> existingSkills,
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
            double random = Math.random();
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

    public static Skill createRandomSkill(Collection<Skill> existingSkills, String name) {
        SkillType type = typeInNeedOfLove(
                existingSkills,
                TARGET_PRIMARY_SKILL_DENSITY,
                TARGET_SECONDARY_SKILL_DENSITY,
                TARGET_TERTIARY_SKILL_DENSITY,
                Collection::size)
                .type;
        return createSkill(name, type, existingSkills);
    }

    public static Skill createSkill(String name, SkillType type, Collection<Skill> allSkills) {
        Skill skill = new Skill(name, type);
        do {
            addNewProductLineToSkill(skill, allSkills);
        } while (needsNewProductLine(skill, allSkills));
        return skill;
    }

    public static void addNewProductLineToSkill(Skill skill, Collection<Skill> allSkills) {
        String name = skill.name + "{" + (skill.productLines.size()) + "}";
        switch (skill.type) {
            case PRIMARY -> skill.productLines.add(ProductLineFactory.createPrimaryProductLine(skill, name));
            case SECONDARY ->
                    skill.productLines.add(ProductLineFactory.createSecondaryProductLine(skill, name, allSkills.stream()
                            .filter(s -> PRIMARY.equals(s.type)).toList()));
            case TERTIARY ->
                    skill.productLines.add(ProductLineFactory.createTertiaryProductLine(skill, name, allSkills.stream()
                            .filter(s -> SkillType.SECONDARY.equals(s.type)).toList()));
        }
    }

    public static boolean correctProductLineDensity = true;

    private static boolean needsNewProductLine(Skill target, Collection<Skill> allSkills) {
        if (!correctProductLineDensity) {
            return false;
        }
        Collection<Skill> all = new ArrayList<>(allSkills);
        all.add(target);

        WeightedSkillTypeChoice choice = typeInNeedOfLove(all,
                TARGET_PRODUCT_LINE_DENSITY_PRIMARY,
                TARGET_PRODUCT_LINE_DENSITY_SECONDARY,
                TARGET_PRODUCT_LINE_DENSITY_TERTIARY,
                skills -> skills.stream().flatMap(s -> s.productLines.stream()).toList().size());

        return choice.weight > TOLERANCE && choice.type == target.type;
    }
}
